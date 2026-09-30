package com.killer560.hub.secrets;

import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.ChatObserver;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import org.slf4j.Logger;
import com.killer560.hub.util.ModLog;

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
    /**
     * ANCHORED, because this is matched with find() against any chat line he can see.
     *
     * <p>Unanchored, {@code [VIP] Bob: [BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!} typed by a stranger
     * in any channel started the boss phase for the rest of the run - which switches OFF every clear-phase
     * feature (the puzzle solvers, Auto Puzzles, Door Helpers, Wither Doors) and arms the boss-only ones
     * during the clear. It only clears when the floor changes, so one message ruined the run.
     *
     * <p>A real server line begins with the bracket; a player's message always has their name and a colon in
     * front of it, so anchoring at the start is the whole fix.
     */
    private static final Pattern BOSS_START_PATTERN =
            Pattern.compile("^\\[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!");

    private static final Logger LOGGER = ModLog.get("killer560smod-secrets");

    private static boolean bossPhaseActive = false;
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

    private DungeonState() {
    }

    public static void register() {
        // ChatObserver covers both Fabric channels (signed player-chat and system-message paths - an NPC/boss
        // line can arrive through either) AND lines another mod (Odin/NoammAddons/Skyblocker) cancelled via
        // ALLOW_GAME and re-added straight to ChatComponent, which Fabric listeners never see. The boss-phase
        // trigger is Maxor's full, exact opening sentence, so this mod's own client-side messages (also
        // delivered by ChatObserver) can't start the boss phase. Overlay (action bar) lines are not delivered - none needed here.
        ChatObserver.subscribe(DungeonState::onChatMessage);
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("DungeonState", client -> {
            if (client.level == null && simOverrideActive) {
                LOGGER.info("[Secrets] World unloaded - clearing /killer560 sim override.");
                simOverrideActive = false;
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
                LOGGER.info("[Secrets] Dungeon floor changed: '{}' -> '{}'", cachedFloor, floor);
                cachedFloor = floor;
            }
            boolean f7OrM7Now = "F7".equals(floor) || "M7".equals(floor);
            if (bossPhaseActive && !f7OrM7Now) {
                LOGGER.info("[Secrets] Boss phase ended (left F7/M7, floor now '{}')", floor);
                bossPhaseActive = false;
            }
        }));
    }

    private static void onChatMessage(Component message) {
        String raw = message.getString();
        String plain = ChatFormatting.stripFormatting(raw);
        if (plain != null && BOSS_START_PATTERN.matcher(plain).find() && isF7OrM7()) {
            LOGGER.info("[Secrets] Boss phase started (real Maxor chat line matched)");
            bossPhaseActive = true;
        } else if (plain != null && BOSS_START_PATTERN.matcher(plain).find()) {
            LOGGER.warn("[DungeonState] Maxor start line matched but isF7OrM7()=false (floor='{}') - boss phase NOT started",
                    cachedFloor);
        }
    }

    /**
     * The dungeon sim is running a CLEAR.
     *
     * <p>Separate from {@link #simOverrideActive} on purpose, and the difference is the whole point. That one
     * forces floor, F7 and BOSS PHASE on together, so it shuts the gate on every feature that requires not being
     * in the boss - which is most of the clear features, and exactly the ones the sim exists to practise with.
     * This one says "in a dungeon, on F7, not in the boss", which is what a clear actually is.
     *
     * <p>killer560 asked that "configuring secret routes or auto routes and whatnot works in this sim". Those
     * all gate on {@link #isInDungeon()}, and without this they would every one of them sit out.
     */
    private static boolean roomSimActive;

    /** Turned on while a sim clear is loaded; off the moment it ends. */
    public static void setRoomSim(boolean active) {
        roomSimActive = active;
    }

    public static boolean isRoomSim() {
        return roomSimActive;
    }

    public static boolean isInDungeon() {
        return simOverrideActive || roomSimActive || cachedFloor != null;
    }

    /** @return the raw floor string (e.g. "F7", "M3"), or null outside a dungeon run - added for
     *  {@link com.killer560.hub.splittimers.SplitTimersFeature}, which needs to pick the right split
     *  list per floor rather than just the boolean F7/M7 check the rest of this mod uses. Forced to
     *  "F7" while {@link #isSimOverrideActive()}. */
    public static String getFloor() {
        if (simOverrideActive) {
            return "F7";
        }
        // M7 for the sim: the clear features he practises with are the M7 ones, and Auto Debuff is M7-only.
        return roomSimActive ? "M7" : cachedFloor;
    }

    public static boolean isF7OrM7() {
        return simOverrideActive || roomSimActive || "F7".equals(cachedFloor) || "M7".equals(cachedFloor);
    }

    public static boolean isBossPhaseActive() {
        // NOT roomSimActive: a sim clear is explicitly not the boss, and saying otherwise would close the gate
        // on every feature the sim is for.
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
    /**
     * The room name off the sidebar's {@code Room: X} line, or null.
     *
     * <p>Ashfall's single-room practice worlds put up a sidebar reading {@code Practice Room / Room: Tombstone}
     * with no {@code The Catacombs} line, so {@link #isInDungeon()} is correctly false there and the Live Map
     * never builds a layout. That name is then the only thing identifying the room, which is what the Room
     * Recorder's single-room capture runs on.
     */
    public static String sidebarRoomName() {
        String raw = readSidebarText();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        for (String line : raw.split("\n")) {
            String t = line.trim();
            if (t.startsWith("Room:")) {
                String name = t.substring("Room:".length()).trim();
                return name.isEmpty() ? null : name;
            }
        }
        return null;
    }

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
