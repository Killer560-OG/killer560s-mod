package com.killer560.hub.secrets;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
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
 *  boss fight always opens with the identical line
 *  {@code §4[BOSS] Maxor§r§c: §r§cWELL! WELL! WELL! LOOK WHO'S HERE!} (confirmed via SkyHanni's own
 *  {@code maxorStartPattern}, and this exact mod's own boot-test log this session independently showed
 *  the real {@code [BOSS] Goldor: Who dares trespass into my domain?} follow-up line from the same
 *  fight) - only the fight's opening line is needed here since Levers/Buttons expansion just needs "is
 *  the boss phase active right now", not which exact sub-phase. Ends when the floor is no longer F7/M7
 *  (leaving the dungeon, or - defensively - if the scoreboard ever reports something else). */
public final class DungeonState {

    private static final Pattern CATACOMBS_FLOOR_PATTERN = Pattern.compile("The Catacombs \\(([^)]+)\\)");
    // Real line confirmed via SkyHanni's own maxorStartPattern (decompiled 2026-09-09) and this mod's
    // own earlier boot-test log this session, which independently captured the real follow-up
    // "[BOSS] Goldor: Who dares trespass into my domain?" line from the same F7 boss fight.
    private static final Pattern BOSS_START_PATTERN =
            Pattern.compile("§4\\[BOSS] Maxor§r§c: §r§cWELL! WELL! WELL! LOOK WHO'S HERE!");

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
                LOGGER.info("[Secrets] Dungeon floor changed: '{}' -> '{}'", cachedFloor, floor);
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
        if (BOSS_START_PATTERN.matcher(message.getString()).find() && isF7OrM7()) {
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

    private static String readSidebarText() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.level == null) {
            return "";
        }
        Scoreboard scoreboard = client.level.getScoreboard();
        Objective sidebar = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR);
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
