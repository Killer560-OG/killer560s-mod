package com.killer560.hub.secrets;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
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
    // every ~3s (not just on change, since it may never be changing at all) - which DisplaySlot (if any)
    // actually holds a non-null objective, and the raw text read from it - so the next real dungeon run
    // shows definitively whether SIDEBAR itself is populated, some other slot is, or none are.
    private static int diagnosticTickCounter = 0;
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

    private DungeonState() {
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
            diagnosticTickCounter++;
            if (diagnosticTickCounter >= 60) {
                diagnosticTickCounter = 0;
                logSidebarDiagnostic();
            }
            String floor = computeCurrentFloor();
            if (!Objects.equals(floor, cachedFloor)) {
                // Includes a snippet of the raw sidebar text this round - if the DisplaySlot fallback fix
                // (2026-09-09, round 13) still isn't enough, this is the next thing to check: is a real
                // sidebar even being found now, and does its actual text match CATACOMBS_FLOOR_PATTERN.
                String rawSidebar = readSidebarText();
                String snippet = rawSidebar.length() > 200 ? rawSidebar.substring(0, 200) + "..." : rawSidebar;
                LOGGER.info("[Secrets] Dungeon floor changed: '{}' -> '{}' (sidebar: \"{}\")",
                        cachedFloor, floor, snippet.replace("\n", "\\n"));
                cachedFloor = floor;
            }
            boolean f7OrM7Now = "F7".equals(floor) || "M7".equals(floor);
            if (bossPhaseActive && !f7OrM7Now) {
                LOGGER.info("[Secrets] Boss phase ended (left F7/M7, floor now '{}')", floor);
                bossPhaseActive = false;
            }
        });
    }

    private static void onChatMessage(Component message) {
        String raw = message.getString();
        // Broad safety net - if the plain-text match below still somehow misses the real line, this
        // logs the EXACT raw text (formatting codes and all) of anything boss-related so the actual
        // wording/codes can be compared directly instead of guessing again.
        if (raw.contains("Maxor") || raw.contains("[BOSS]")) {
            LOGGER.info("[Secrets] Boss-related chat line seen: \"{}\"", raw);
        }
        String plain = ChatFormatting.stripFormatting(raw);
        if (plain != null && BOSS_START_PATTERN.matcher(plain).find() && isF7OrM7()) {
            LOGGER.info("[Secrets] Boss phase started (real Maxor chat line matched)");
            bossPhaseActive = true;
        }
    }

    public static boolean isInDungeon() {
        return cachedFloor != null;
    }

    public static boolean isF7OrM7() {
        return "F7".equals(cachedFloor) || "M7".equals(cachedFloor);
    }

    public static boolean isBossPhaseActive() {
        return bossPhaseActive && isF7OrM7();
    }

    /** @return the floor string (e.g. "F7", "M7", "E") from the sidebar scoreboard, or null if not
     *  currently in a Catacombs run - same "contains the title, not on the queue/party screen" logic
     *  NoammAddons' own {@code LocationUtils} uses. Only called once per tick (see {@link #register}) -
     *  {@link #isInDungeon()}/{@link #isF7OrM7()} read the cached result instead of re-parsing the
     *  scoreboard on every call. */
    private static String computeCurrentFloor() {
        String sidebar = readSidebarText();
        if (sidebar.isEmpty() || sidebar.contains("Queue")) {
            return null;
        }
        Matcher matcher = CATACOMBS_FLOOR_PATTERN.matcher(sidebar);
        return matcher.find() ? matcher.group(1) : null;
    }

    /** Diagnostic only (round 21) - see the doc comment on {@link #diagnosticTickCounter}. Logs whether
     *  the plain {@code DisplaySlot.SIDEBAR} objective is populated, and if not, EVERY other
     *  {@code DisplaySlot} that has a non-null objective (not just the first one found, unlike
     *  {@link #readSidebarText()}) - plus the actual raw text {@link #readSidebarText()} ends up reading.
     *  Runs every ~3 real seconds (60 client ticks) regardless of whether the floor value has changed,
     *  since the bug being chased is that it may never be changing at all. */
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
        String snippet = raw.length() > 200 ? raw.substring(0, 200) + "..." : raw;
        LOGGER.info("[Secrets] Sidebar diagnostic: plainSidebar={} otherPopulatedSlots=[{}] readSidebarText=\"{}\"",
                plainSidebar != null ? "'" + plainSidebar.getDisplayName().getString() + "'" : "null",
                others.toString().trim(), snippet.replace("\n", "\\n"));
    }

    /** Round 13 (2026-09-09) added a fallback here from a theory that Hypixel renders its real sidebar
     *  through one of the {@code TEAM_*} colored slots instead of plain {@code DisplaySlot.SIDEBAR}. That
     *  theory is now suspect (round 21, 2026-09-09): killer560 confirmed Dungeons Only still never
     *  enables during a real dungeon run even with every individual block toggle working, and that
     *  session's log never once showed a floor change across a full F7 clear. SkyHanni's own real,
     *  currently-working sidebar reader (decompiled 2026-09-09 - {@code ScoreboardCompatKt
     *  .getSidebarObjective}) is just a bare {@code getDisplayObjective(DisplaySlot.SIDEBAR)} with NO
     *  team-color fallback at all, which directly contradicts the round-13 theory. The fallback loop below
     *  is left in place (harmless if {@code DisplaySlot.SIDEBAR} is populated, since it only runs when
     *  that's null), but the real bug is evidently elsewhere - see {@link #logSidebarDiagnostic()}, added
     *  this round specifically to pin it down with real data instead of another guess. */
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
            sb.append(entry.display() != null ? entry.display().getString() : entry.owner()).append('\n');
        }
        return sb.toString();
    }
}
