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

    /** Real bug found and fixed (2026-09-09, round 13) - killer560's own logging (added round 12) caught
     *  ZERO floor-change transitions across a real session that entered 3 different Catacombs floors back
     *  to back, meaning this was never detecting being in a dungeon AT ALL, not just failing to re-enable
     *  on re-entry as originally reported (which then follows trivially: it can't turn something back on
     *  that it never detected turning on in the first place). Root cause: {@code DisplaySlot.SIDEBAR} is
     *  the plain, uncolored sidebar slot, but Hypixel (like many servers) commonly renders its real
     *  sidebar through one of the 15 {@code TEAM_*} colored slots instead - a well-known vanilla
     *  scoreboard quirk servers use to route around per-line color/format limits on the plain slot. This
     *  mod's own {@code LocationTracker} (RNG meter) has the exact same never-verified assumption per its
     *  own doc comment - not a proven-working reference after all, just an equally untested copy of the
     *  same approach. Falls back to whichever {@code TEAM_*} slot is actually populated if the plain one
     *  isn't. */
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
