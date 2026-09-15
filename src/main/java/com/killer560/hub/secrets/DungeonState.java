package com.killer560.hub.secrets;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Real dungeon-floor and boss-phase detection - killer560's explicit "dungeons only" / "boss only"
 *  request (2026-09-09), grounded in two references (decompiled 2026-09-09):
 *  <ul>
 *  <li>Floor detection: NoammAddons' own {@code LocationUtils} reads the Hypixel sidebar scoreboard for
 *  a line containing "The Catacombs (" (excluding a "Queue" line, to avoid the party-finder screen),
 *  then takes the text between the parens as the floor string (e.g. "F7", "M7") - the "M" prefix means
 *  Master Mode. This mod already has real, working scoreboard-reading code for the exact same sidebar
 *  ({@link com.killer560.hub.rngmeter.LocationTracker}) - this mirrors that same technique rather than
 *  inventing a new one.
 *  <li>Boss-phase detection: SkyHanni's own {@code DungeonBossApi} tracks it via real boss chat lines
 *  (not world coordinates - NoammAddons uses a per-floor {@code AABB} bounding-box approach instead, but
 *  that needs real boss-room coordinate data this mod doesn't have verified for Master Mode specifically,
 *  so the chat-message approach was used here as the safer, simpler-to-verify option). The real F7/M7
 *  boss fight always opens with the line {@code [BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!}
 *  (confirmed via SkyHanni's own {@code maxorStartPattern}) - only the fight's opening line is needed
 *  here since Levers/Buttons expansion just needs "is the boss phase active right now", not which exact
 *  sub-phase. Ends when the floor is no longer F7/M7 (leaving the dungeon, or - defensively - if the
 *  scoreboard ever reports something else). Matched on the plain text with formatting stripped first -
 *  an earlier version required an exact match on hardcoded color codes, which silently never matched a
 *  real boss fight (killer560's report: Boss Only never enabled full-block even during a real F7 fight). */
public final class DungeonState {

    private static final Pattern CATACOMBS_FLOOR_PATTERN = Pattern.compile("The Catacombs \\(([^)]+)\\)");
    // Real bug found and fixed (2026-09-09, round after the Dungeons Only fix) - killer560 reported Boss
    // Only still never enabled full-block even during a real F7 boss fight. This pattern required an
    // EXACT match on hardcoded color codes (§4, §r, §c) - a single real formatting difference (extra/
    // missing reset code, a slightly different color) would silently never match, and unlike the floor
    // pattern above there was no logging on this path to catch it. Matching on the plain text instead
    // (formatting stripped first) is far more robust - color codes are cosmetic, the words are what
    // actually identify the line.
    private static final Pattern BOSS_START_PATTERN =
            Pattern.compile("\\[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!");

    /** Logging filter only - e.g. {@code Maxor's Frenzy hit you for 1,959.3 damage.} (real run log). */
    private static final Pattern BOSS_DAMAGE_SPAM_PATTERN = Pattern.compile("^(?!\\[BOSS]).*'s .+ hit you for [\\d,.]+ damage\\.?$");

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-secrets");

    private static boolean bossPhaseActive = false;
    // Real bug found (2026-09-09, round 21): killer560 confirmed every individual block toggle works
    // fine (Full Block master ON, Dungeons Only OFF), but Dungeons Only itself never enables during a
    // real dungeon run. A real F7 clear's log (all 5 bosses fought) never logged a SINGLE "Dungeon floor
    // changed" line - meaning #computeCurrentFloor() returned null the entire run, not just failed to
    // re-enable on re-entry. SkyHanni's own real, currently-working sidebar reader
    // (ScoreboardCompatKt.getSidebarObjective, decompiled 2026-09-09) is just a bare
    // `getDisplayObjective(DisplaySlot.SIDEBAR)` with NO team-color fallback at all - directly
    // contradicting this class's own round-13 "Hypixel uses a TEAM_* slot instead" theory, which was
    // never independently confirmed against a real log and looks to have been the wrong diagnosis.
    // Rather than guess again on this HIGH-RISK-adjacent detection code, this logs the full picture
    // (polled every ~3s; since 2026-09-14 only logged when it changes) - which DisplaySlot (if any)
    // actually holds a non-null objective, and the raw text read from it - so the next real dungeon run
    // shows definitively whether SIDEBAR itself is populated, some other slot is, or none are.
    private static int diagnosticTickCounter = 0;
    // "/killer560 sim" (2026-09-14) - killer560's own request, since p3sim.net's real sidebar/chat
    // format was unknown and this session had no way to connect and observe it directly. Rather than
    // guess at matching p3sim's real text (this class's own history above is full of real, hard-won
    // lessons about guessing at Hypixel's exact text/formatting instead of confirming it), this is a
    // manual escape hatch: killer560 tells the mod directly "I am in the F7 boss fight right now"
    // instead of the mod trying to detect it automatically. Purely manual - only clears when killer560
    // toggles it off himself, or when the world unloads entirely (server switch/disconnect). A real
    // p3sim.net test (2026-09-14) proved auto-clearing on "any real floor detected" was wrong - real
    // detection can succeed for one signal (floor) while the override is still doing real work for
    // another (e.g. boss-phase state that hasn't caught up yet), so that auto-clear was removed.
    private static boolean simOverrideActive = false;
    // Per killer560's "relook through the other mods... otherwise put some sort of logging into my game"
    // request (2026-09-09, round 12) - re-checked NoammAddons' own LocationUtils (decompiled) for how it
    // tracks the same dungeon state; its approach is architecturally different (a one-shot "detect
    // entering" flag flipped by a specific packet event, reset on leaving) rather than this class's
    // continuous re-parse-every-call approach, but nothing in it points to a concrete bug in this class's
    // own logic. Since Secrets/#passesDungeonsOnlyGate is real, HIGH RISK collision code, guessing at a
    // fix without being able to verify it isn't worth the risk - so instead this now recomputes the
    // floor ONCE per tick (not on every #isInDungeon()/#isF7OrM7() call, which can be very frequent - a
    // real block's getShape() is queried often) and logs every time it actually CHANGES, so a real
    // dungeon run's log can show exactly what this class saw and when.
    private static String cachedFloor;

    // [DungeonState] diagnostics (2026-09-14, pre-live-run logging pass) - logging only, never gates anything.
    private static String lastGateSnapshot = null;
    private static int tabClassTickCounter = 0;
    private static String lastTabClassSummary = null;
    private static final Pattern TAB_CLASS_PATTERN =
            Pattern.compile("\\((Mage|Tank|Healer|Archer|Berserk|Berserker|EMPTY|DEAD)[^)]*\\)");
    private static final String[] DUNGEON_CHAT_KEYWORDS = {
            "[NPC] Mort", "Starting in", "entered", "Wither Key", "Blood Key", "WITHER door", "BLOOD DOOR",
            "PUZZLE", "EXTRA STATS", "Mimic", "Prince", "[STATUE]", "Watcher", "RIGHT CLICK", "has obtained",
            "Dungeon starts", "Team Score", "Defeated", "The Core entrance", "terminal", "device", "gate"
    };

    private DungeonState() {
    }

    /** Player position for logs, or "no-player". */
    private static String playerPosForLog() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null) {
            return "no-player";
        }
        var pos = client.player.position();
        return String.format(java.util.Locale.US, "(%.2f, %.2f, %.2f)", pos.x, pos.y, pos.z);
    }

    private static String serverIpForLog() {
        Minecraft client = Minecraft.getInstance();
        if (client == null) {
            return "null";
        }
        var server = client.getCurrentServer();
        return server == null ? "null" : String.valueOf(server.ip);
    }

    /** Logs whenever any externally visible gate value changes (what every dungeon feature reads). */
    private static void logGateSnapshotIfChanged() {
        String snapshot = "inDungeon=" + isInDungeon() + " floor=" + getFloor() + " f7OrM7=" + isF7OrM7()
                + " bossPhaseRaw=" + bossPhaseActive + " bossPhaseEffective=" + isBossPhaseActive()
                + " simOverride=" + simOverrideActive;
        if (!snapshot.equals(lastGateSnapshot)) {
            LOGGER.info("[DungeonState] Gates changed: {} -> {} | pos={} server={}",
                    lastGateSnapshot, snapshot, playerPosForLog(), serverIpForLog());
            lastGateSnapshot = snapshot;
        }
    }

    /** Tab-list class/party readout - there's no automatic class detection in this mod (classes are
     *  assigned manually in Leap Menu), so this logs what Hypixel's tab list actually shows, every ~5s
     *  while in a dungeon, only when it changes. */
    private static void logTabClassesIfChanged(Minecraft client) {
        if (!isInDungeon() || client.getConnection() == null) {
            tabClassTickCounter = 0;
            return;
        }
        if (++tabClassTickCounter < 100) {
            return;
        }
        tabClassTickCounter = 0;
        StringBuilder summary = new StringBuilder();
        int listed = 0;
        for (var info : client.getConnection().getListedOnlinePlayers()) {
            listed++;
            Component display = info.getTabListDisplayName();
            if (display == null) {
                continue;
            }
            String plain = ChatFormatting.stripFormatting(display.getString());
            if (plain != null && TAB_CLASS_PATTERN.matcher(plain).find()) {
                summary.append('"').append(plain.trim()).append("\" ");
            }
        }
        String result = summary.toString().trim();
        if (!result.equals(lastTabClassSummary)) {
            LOGGER.info("[DungeonState] Tab-list class entries ({} listed players): [{}]", listed,
                    result.isEmpty() ? "NONE MATCHED" : result);
            lastTabClassSummary = result;
        }
    }

    public static void register() {
        // Both channels, matching AutoMeowFeature/MagicFindTracker's own established reasoning: Hypixel
        // sends what looks like normal chat through either the signed player-chat path or the
        // system-message path depending on the message, and an NPC/boss line is exactly the kind that's
        // easy to get wrong by only listening on one.
        ClientReceiveMessageEvents.CHAT.register(
                (message, signedMessage, sender, params, receptionTimestamp) -> onChatMessage(message));
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> onChatMessage(message));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.level == null && simOverrideActive) {
                LOGGER.info("[Secrets] World unloaded - clearing /killer560 sim override.");
                simOverrideActive = false;
            }
            diagnosticTickCounter++;
            if (diagnosticTickCounter >= 60) {
                diagnosticTickCounter = 0;
                logSidebarDiagnostic();
            }
            String floor = computeCurrentFloor();
            // Real bug found and fixed (2026-09-14): this used to auto-clear the override the instant
            // ANY real floor was detected, on the theory that real detection had "taken over" - but a
            // real p3sim.net test showed real floor detection succeeding within the same second the
            // override was toggled on, self-cancelling it almost immediately even though the override
            // might still have been needed for something else (e.g. boss-phase state that hadn't
            // caught up yet). Floor detection alone isn't sufficient evidence the override is no longer
            // needed - only a real disconnect/world-unload (handled above) does that now. The override
            // is purely manual again: on until killer560 turns it off himself.
            if (!Objects.equals(floor, cachedFloor)) {
                // Includes a snippet of the raw sidebar text this round - if the DisplaySlot fallback fix
                // (2026-09-09, round 13) still isn't enough, this is the next thing to check: is a real
                // sidebar even being found now, and does its actual text match CATACOMBS_FLOOR_PATTERN.
                String rawSidebar = readSidebarText();
                String snippet = rawSidebar.length() > 200 ? rawSidebar.substring(0, 200) + "..." : rawSidebar;
                LOGGER.info("[Secrets] Dungeon floor changed: '{}' -> '{}' (sidebar: \"{}\")",
                        cachedFloor, floor, snippet.replace("\n", "\\n"));
                LOGGER.info("[DungeonState] Floor transition '{}' -> '{}' at pos={} server={} simOverride={}",
                        cachedFloor, floor, playerPosForLog(), serverIpForLog(), simOverrideActive);
                cachedFloor = floor;
            }
            boolean f7OrM7Now = "F7".equals(floor) || "M7".equals(floor);
            if (bossPhaseActive && !f7OrM7Now) {
                LOGGER.info("[Secrets] Boss phase ended (left F7/M7, floor now '{}')", floor);
                bossPhaseActive = false;
            }
            logGateSnapshotIfChanged();
            logTabClassesIfChanged(client);
        });
    }

    private static void onChatMessage(Component message) {
        String raw = message.getString();
        // Broad safety net - if the plain-text match below still somehow misses the real line, this
        // logs the EXACT raw text (formatting codes and all) of anything boss-related so the actual
        // wording/codes can be compared directly instead of guessing again.
        // Real bug found and fixed (2026-09-14, first real F7 run log): this also logged Maxor's damage
        // spam ("Maxor's Frenzy/Shadow Wave/Wither TNT hit you for N damage.") - 24 of 123 lines in one
        // fight. Damage lines are skipped now; real [BOSS] dialogue and other Maxor lines still log.
        if ((raw.contains("Maxor") || raw.contains("[BOSS]")) && !BOSS_DAMAGE_SPAM_PATTERN.matcher(Objects.requireNonNullElse(ChatFormatting.stripFormatting(raw), raw)).find()) {
            LOGGER.info("[Secrets] Boss-related chat line seen: \"{}\" (pos={} floor={} bossPhaseRaw={})",
                    raw, playerPosForLog(), cachedFloor, bossPhaseActive);
        }
        String plain = ChatFormatting.stripFormatting(raw);
        if (plain != null && BOSS_START_PATTERN.matcher(plain).find() && isF7OrM7()) {
            LOGGER.info("[Secrets] Boss phase started (real Maxor chat line matched)");
            bossPhaseActive = true;
        } else if (plain != null && BOSS_START_PATTERN.matcher(plain).find()) {
            LOGGER.warn("[DungeonState] Maxor start line matched but isF7OrM7()=false (floor='{}') - boss phase NOT started",
                    cachedFloor);
        }
        // Non-boss dungeon structure lines (run start, keys, doors, puzzles, score) - chat-rate only.
        if (plain != null && (isInDungeon() || plain.contains("Catacombs")) && !raw.contains("[BOSS]")) {
            for (String keyword : DUNGEON_CHAT_KEYWORDS) {
                if (plain.contains(keyword)) {
                    LOGGER.info("[DungeonState] Dungeon chat (matched '{}'): \"{}\" pos={}", keyword, plain, playerPosForLog());
                    break;
                }
            }
        }
    }

    public static boolean isInDungeon() {
        return simOverrideActive || cachedFloor != null;
    }

    /** @return the raw floor string (e.g. "F7", "M3"), or null outside a dungeon run - added for
     *  {@link com.killer560.hub.splittimers.SplitTimersFeature}, which needs to pick the right split
     *  list per floor rather than just the boolean F7/M7 check the rest of this mod uses. Forced to
     *  "F7" while {@link #isSimOverrideActive()}. */
    public static String getFloor() {
        return simOverrideActive ? "F7" : cachedFloor;
    }

    public static boolean isF7OrM7() {
        return simOverrideActive || "F7".equals(cachedFloor) || "M7".equals(cachedFloor);
    }

    public static boolean isBossPhaseActive() {
        return simOverrideActive || (bossPhaseActive && isF7OrM7());
    }

    public static boolean isSimOverrideActive() {
        return simOverrideActive;
    }

    /** For {@code /killer560 sim} - killer560's own manual override for testing on p3sim.net, whose
     *  real sidebar/chat format this session has no way to observe directly. @return the new state. */
    public static boolean toggleSimOverride() {
        simOverrideActive = !simOverrideActive;
        LOGGER.info("[Secrets] /killer560 sim override toggled {}", simOverrideActive ? "ON" : "OFF");
        return simOverrideActive;
    }

    /** @return the floor string (e.g. "F7", "M7", "E") from the sidebar scoreboard, or null if not
     *  currently in a Catacombs run - same "contains the title, not on the queue/party screen" logic
     *  NoammAddons' own {@code LocationUtils} uses. Only called once per tick (see {@link #register}) -
     *  {@link #isInDungeon()}/{@link #isF7OrM7()} read the cached result instead of re-parsing the
     *  scoreboard on every call. */
    private static String computeCurrentFloor() {
        String sidebar = readSidebarText();
        if (sidebar.isEmpty()) {
            return null;
        }
        // Real bug found and fixed (2026-09-09, round 23) - killer560's own log from AFTER the round-22
        // fix proved the sidebar text itself was now being read correctly, but Hypixel embeds literal
        // color codes MID-WORD (e.g. "The Catac§combs §7(F7)"), which the plain CATACOMBS_FLOOR_PATTERN
        // can't match through. Stripped first, the same way BOSS_START_PATTERN already handles it above.
        String plain = ChatFormatting.stripFormatting(sidebar);
        if (plain == null || plain.contains("Queue")) {
            return null;
        }
        Matcher matcher = CATACOMBS_FLOOR_PATTERN.matcher(plain);
        return matcher.find() ? matcher.group(1) : null;
    }

    /** Diagnostic only (round 21) - see the doc comment on {@link #diagnosticTickCounter}. Logs whether
     *  the plain {@code DisplaySlot.SIDEBAR} objective is populated, and if not, EVERY other
     *  {@code DisplaySlot} that has a non-null objective (not just the first one found, unlike
     *  {@link #readSidebarText()}) - plus the actual raw text {@link #readSidebarText()} ends up reading.
     *  Polled every ~3 real seconds (60 client ticks), but only LOGS when the sidebar changes (digits
     *  masked) - see the 2026-09-14 note in the body. */
    private static void logSidebarDiagnostic() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.level == null) {
            return;
        }
        Scoreboard scoreboard = client.level.getScoreboard();
        Objective plainSidebar = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR);
        StringBuilder others = new StringBuilder();
        for (DisplaySlot slot : DisplaySlot.values()) {
            if (slot == DisplaySlot.SIDEBAR) {
                continue;
            }
            Objective candidate = scoreboard.getDisplayObjective(slot);
            if (candidate != null) {
                others.append(slot).append("='").append(candidate.getDisplayName().getString()).append("' ");
            }
        }
        String raw = readSidebarText();
        String plainSidebarName = plainSidebar != null ? "'" + plainSidebar.getDisplayName().getString() + "'" : "null";
        // Real bug found and fixed (2026-09-14, first real F7 run log): this logged every 60 ticks all
        // session, hub included (~20 identical lines/min), truncated to 200 chars so the dungeon lines past
        // "Time Elapsed:" were cut off. Now logs the FULL text, only when the sidebar changes. Digits are
        // masked for the change check only - the clock, Time Elapsed, Purse and teammate HP tick
        // constantly and would otherwise make every poll a "change".
        String changeKey = (plainSidebarName + "|" + others + "|" + raw).replaceAll("\\d", "#");
        if (changeKey.equals(lastSidebarDiagnosticKey)) {
            return;
        }
        lastSidebarDiagnosticKey = changeKey;
        LOGGER.info("[Secrets] Sidebar diagnostic (changed): plainSidebar={} otherPopulatedSlots=[{}] readSidebarText=\"{}\"",
                plainSidebarName, others.toString().trim(), raw.replace("\n", "\\n"));
    }

    private static String lastSidebarDiagnosticKey = null;

    /** Real bug found and fixed (2026-09-09, round 22) - your own log from a real F7 clear (Maxor's
     *  opening chat line included) showed the actual root cause: {@code DisplaySlot.SIDEBAR} WAS
     *  populated the whole time (round 13's "wrong slot" theory was a red herring), but every line came
     *  back as literal garbage like {@code "§j"}/{@code "§t"} - no visible text at all. Hypixel's real
     *  anti-scraping technique: {@link PlayerScoreEntry#owner()} is just a per-line unique INVISIBLE
     *  color-code key, not real text - the actual visible line is rendered from that owner's registered
     *  {@link PlayerTeam}'s prefix + suffix instead. This mod's code was reading {@code entry.display()}
     *  (null - Hypixel doesn't set it) falling back to {@code entry.owner()} (the invisible key itself),
     *  so it could never have matched real text. Confirmed against SkyHanni's own real, working
     *  {@code ScoreboardCompatKt.getPlayerNames} (decompiled 2026-09-09), which reconstructs each line
     *  from {@code scoreboard.getPlayersTeam(entry.owner())}'s prefix/suffix - this mirrors that exactly. */
    private static String readSidebarText() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.level == null) {
            return "";
        }
        Scoreboard scoreboard = client.level.getScoreboard();
        Objective sidebar = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR);
        if (sidebar == null) {
            for (DisplaySlot slot : DisplaySlot.values()) {
                if (slot == DisplaySlot.SIDEBAR || slot == DisplaySlot.LIST || slot == DisplaySlot.BELOW_NAME) {
                    continue;
                }
                Objective candidate = scoreboard.getDisplayObjective(slot);
                if (candidate != null) {
                    sidebar = candidate;
                    break;
                }
            }
        }
        if (sidebar == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(sidebar.getDisplayName().getString()).append('\n');
        for (PlayerScoreEntry entry : scoreboard.listPlayerScores(sidebar)) {
            sb.append(realLineText(scoreboard, entry)).append('\n');
        }
        return sb.toString();
    }

    /** @return the real visible text for one sidebar line - see {@link #readSidebarText()}'s doc comment
     *  for why this can't just be {@code entry.display()}/{@code entry.owner()}. Falls back to those only
     *  if the entry's owner has no registered team at all (shouldn't normally happen on Hypixel, but
     *  cheaper/safer than returning nothing). */
    private static String realLineText(Scoreboard scoreboard, PlayerScoreEntry entry) {
        PlayerTeam team = scoreboard.getPlayersTeam(entry.owner());
        if (team == null) {
            return entry.display() != null ? entry.display().getString() : entry.owner();
        }
        StringBuilder line = new StringBuilder();
        Component prefix = team.getPlayerPrefix();
        if (prefix != null) {
            line.append(prefix.getString());
        }
        Component suffix = team.getPlayerSuffix();
        if (suffix != null) {
            line.append(suffix.getString());
        }
        return line.toString();
    }
}
