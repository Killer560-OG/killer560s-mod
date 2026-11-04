package com.killer560.hub.splittimers;

import com.killer560.hub.doorhelpers.DoorHelpersFeature;
import com.killer560.hub.dungeonclass.DungeonClass;
import com.killer560.hub.leapmenu.LeapMenuFeature;
import com.killer560.hub.leapmenu.PartyTracker;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.OutgoingChat;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Core entry times (killer560, 2026-09-16: "add a time to enter core after terms finish timer with an option to send
 * slowest to chat") - how long each player takes to get into the core after the terminals are done, on F7/M7.
 * <p>
 * <b>Start of the clock:</b> the real {@code "The Core entrance is opening!"} line - the same trigger this feature's
 * own {@code FLOOR7} split list already uses for the Goldor segment (Odin {@code SplitsManager}), and the one
 * {@code goldorfrenzy/} keys off. Fed in from {@link SplitTimersFeature}'s chat handler, so there is no second chat
 * subscription.
 * <p>
 * <b>Entry detection:</b> Hypixel never announces who entered the core, so entry is a position crossing: the first tick
 * a player stands inside the core itself, the closed room in the middle of the Goldor arena whose entrance opens on that
 * line. The box is NoammAddons' {@code LeapCounter} "in core" check ({@code aabb(68, 106, 54, 42, 155, 119)}: x 42..68,
 * y 106..155, z 54..119; its {@code INCORE_BOX} leap spot is the {@code CORE_BOX} entrance moved 6 blocks in, at z 55..60,
 * and this mod's own Posmsg "Recore" preset sits at 54.5, 115, 58.5 inside it).
 * <p>
 * Until 2026-11-01 it was the P4 band {@code 45 < y <= 100} instead - Necron's platform, under the core - so a player was
 * "in" only once Goldor was dead and they had dropped to Necron, and Necron's first line (which ends the timing) usually
 * came after only the first to drop had been timed: killer560, "We are using 4 mages and 1 arch and it only ever gave
 * the arch's time".
 * <p>
 * <b>Positions:</b> {@link LeapMenuFeature#currentPartyMembers()} - the loaded-player list the Live Map already draws
 * its teammate dots from. No new packet handling. A player outside the server's entity-tracking range has <b>no</b>
 * live position at all, so:
 * <ul>
 * <li>A player never seen inside the band gets a dash ({@code -}), never a made-up number.
 * <li>A player who was unloaded while crossing is timed at the first tick they are loaded <i>and</i> already inside
 * the band, which is an upper bound on their real entry, not an exact time.
 * </ul>
 * <p>
 * One measurement per player per run; everything resets on world change / run start / leaving the dungeon
 * ({@link SplitTimersFeature} drives all three). The slowest-player announcements fire at most once per run.
 */
final class CoreEntryTimes {

    /** Inside the core (NoammAddons LeapCounter's in-core box): x 42..68, y 106..155, z 54..119. */
    static final double CORE_MIN_X = 42.0, CORE_MAX_X = 68.0;
    static final double CORE_MIN_Y = 106.0, CORE_MAX_Y = 155.0;
    static final double CORE_MIN_Z = 54.0, CORE_MAX_Z = 119.0;

    /** True for a position inside the core. */
    static boolean inCore(double x, double y, double z) {
        return x >= CORE_MIN_X && x <= CORE_MAX_X && y >= CORE_MIN_Y && y <= CORE_MAX_Y
                && z >= CORE_MIN_Z && z <= CORE_MAX_Z;
    }
    private static final String CHAT = "Split Timers";

    private static long openedMs = 0L;
    /** Player name -> ms from the core opening to first seen inside the core. */
    private static final Map<String, Long> ENTRIES = new LinkedHashMap<>();
    /** Everyone expected to enter, in party order, self first - snapshotted when the core opens. */
    private static final List<String> EXPECTED = new ArrayList<>();
    private static boolean announced = false;

    private CoreEntryTimes() {
    }

    /** "The Core entrance is opening!" - starts the clock and snapshots who is expected. */
    static void onCoreOpening() {
        reset();
        if (!DungeonState.isF7OrM7()) {
            return;
        }
        openedMs = System.currentTimeMillis();
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            EXPECTED.add(client.player.getGameProfile().name());
        }
        EXPECTED.addAll(PartyTracker.teammates());
        // Anyone already inside when the line arrives entered at 0 (killer560, 2026-11-02: "we were in before it even
        // went off so it should say 0 for us"). Until then the first look was the END_CLIENT_TICK after the line: the
        // line is handled with the frame's packets, before the frame's client ticks (Minecraft.runTick), often a frame
        // or a whole tick before the next END_CLIENT_TICK, so a player standing inside read 0.01-0.05 s instead of 0.
        if (SplitTimersConfig.getInstance().isCoreEntryActive() && client.player != null && client.level != null) {
            recordAll(client, openedMs);
        }
    }

    /** Necron's entry line - the fight has started, nobody else is "entering the core". */
    static void onPhaseFourStarted() {
        announce();
    }

    static void reset() {
        openedMs = 0L;
        ENTRIES.clear();
        EXPECTED.clear();
        announced = false;
    }

    static void tick() {
        if (openedMs == 0L || !SplitTimersConfig.getInstance().isCoreEntryActive()) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) {
            return;
        }
        // Real bug found and fixed (2026-09-20, killer560: "It did pick up one person but it was the wrong
        // person and the wrong time"): EXPECTED used to be snapshotted ONCE in onCoreOpening() and only grew
        // afterward as a side effect of record()'s own "unexpected arrival" fallback below. If the party tab
        // list hadn't finished re-parsing at that exact instant (a real risk right as the floor transitions
        // into "The Core entrance is opening!"), EXPECTED started with only 1-2 names in it. The very first
        // teammate to physically reach the y<=100 band then satisfied "ENTRIES.size() >= EXPECTED.size()" all
        // by itself, so announce() fired immediately and reported that lone early arrival as "slowest" - the
        // wrong person, with a wrong (far too early) time, while the rest of the party hadn't entered yet.
        // PartyTracker's tab list keeps refreshing on its own regardless of this feature, so re-syncing
        // EXPECTED against it every tick (union only, never removing anyone already recorded) means the real
        // full roster is in place well before anyone can actually fall/walk into the core - closing the race.
        refreshExpected(client);
        recordAll(client, System.currentTimeMillis());
        if (!announced && ENTRIES.size() >= EXPECTED.size() && !EXPECTED.isEmpty()) {
            announce();
        }
    }

    /** Times everyone loaded who is inside the core and not yet timed, at {@code now}. */
    private static void recordAll(Minecraft client, long now) {
        record(client.player, now);
        for (Player player : LeapMenuFeature.currentPartyMembers()) {
            if (player.getUUID().version() != 4) {
                continue; // Hypixel NPCs / display entities are v2 UUIDs - same filter Class Colors uses.
            }
            record(player, now);
        }
    }

    private static void refreshExpected(Minecraft client) {
        if (client.player != null) {
            String self = client.player.getGameProfile().name();
            if (!containsIgnoreCase(EXPECTED, self)) {
                EXPECTED.add(self);
            }
        }
        for (String teammate : PartyTracker.teammates()) {
            if (!containsIgnoreCase(EXPECTED, teammate)) {
                EXPECTED.add(teammate);
            }
        }
    }

    private static boolean containsIgnoreCase(List<String> list, String name) {
        if (name == null) {
            return false;
        }
        for (String s : list) {
            if (s.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    private static void record(Player player, long now) {
        String name = player.getGameProfile().name();
        // Case-insensitive: Minecraft usernames are unique case-insensitively, but the tab-list-derived
        // EXPECTED name and this entity's real GameProfile name aren't guaranteed to match case-for-case -
        // a mismatch here would silently create a duplicate "ghost" entry that never resolves (see this
        // batch's staging notes for how that could also produce a wrong slowest-person result).
        if (containsKeyIgnoreCase(ENTRIES, name)) {
            return;
        }
        // A dead player is a ghost that can fly through the core box: never timed (killer560, 2026-11-04: "this person
        // was dead when we went to enter core"). Our own death also shows in the Haunt item.
        if (player == Minecraft.getInstance().player ? DoorHelpersFeature.isDead(Minecraft.getInstance())
                : PartyTracker.isDead(name)) {
            return;
        }
        if (!inCore(player.getX(), player.getY(), player.getZ())) {
            return;
        }
        if (!containsIgnoreCase(EXPECTED, name)) {
            // The party list wasn't complete when the core opened (tab list not parsed yet, late join) - they are
            // clearly in this run, so they join the expected list instead of being timed but never shown.
            EXPECTED.add(name);
        }
        ENTRIES.put(name, now - openedMs);
    }

    private static Long getIgnoreCase(Map<String, Long> map, String name) {
        for (Map.Entry<String, Long> e : map.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) {
                return e.getValue();
            }
        }
        return null;
    }

    private static boolean containsKeyIgnoreCase(Map<String, Long> map, String name) {
        if (name == null) {
            return false;
        }
        for (String key : map.keySet()) {
            if (key.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    /** For the testkit: player name -> ms from the core opening to entering it. */
    static Map<String, Long> entries() {
        return new LinkedHashMap<>(ENTRIES);
    }

    /** HUD/chat rows: everyone expected, in party order; a dash for anyone not in yet, "Did not enter" once timing ended. */
    static List<String> lines() {
        if (openedMs == 0L || EXPECTED.isEmpty() || !SplitTimersConfig.getInstance().isCoreEntryActive()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(EXPECTED.size());
        for (String name : EXPECTED) {
            Long ms = getIgnoreCase(ENTRIES, name);   // the tab list's name and the entity's may differ in case
            out.add(colorFor(name) + name + "§f: " + (ms != null ? format(ms) : announced ? "§cDid not enter" : "§7-"));
        }
        return out;
    }

    /** Sample rows for the HUD editor, so the block can be positioned outside a run. */
    static List<String> editorLines() {
        return List.of("§bYou§f: 2.35s", "§aTeammate§f: 4.10s", "§cTeammate§f: §7-");
    }

    private static String colorFor(String name) {
        DungeonClass clazz = PartyTracker.classOf(name);
        if (clazz == null) {
            return "§f";
        }
        return switch (clazz) {
            case MAGE -> "§9";
            case TANK -> "§a";
            case HEALER -> "§d";
            case ARCHER -> "§c";
            case BERSERKER -> "§6";
        };
    }

    private static String format(long ms) {
        return String.format(Locale.US, "%.2fs", SplitTimersConfig.shown(ms) / 1000.0);
    }

    /** The slowest entry recorded so far, or null before anyone has been timed in. Shared by the chat/party
     *  announce and the HUD's own live "Slowest Into Core" line so they can never disagree. */
    private static Map.Entry<String, Long> slowestEntry() {
        Map.Entry<String, Long> slowest = null;
        for (Map.Entry<String, Long> entry : ENTRIES.entrySet()) {
            if (slowest == null || entry.getValue() > slowest.getValue()) {
                slowest = entry;
            }
        }
        return slowest;
    }

    /** Slowest player into the core, once per run, to Mod Chat and/or party chat - both off by default. */
    private static void announce() {
        if (announced || openedMs == 0L) {
            return;
        }
        announced = true;
        SplitTimersConfig cfg = SplitTimersConfig.getInstance();
        if (!cfg.isCoreEntryActive() || (!cfg.isCoreEntrySlowestChat() && !cfg.isCoreEntrySlowestParty())) {
            return;
        }
        Map.Entry<String, Long> slowest = slowestEntry();
        if (slowest == null) {
            return;
        }
        int missing = EXPECTED.size() - ENTRIES.size();
        String suffix = missing > 0 ? " (" + missing + " did not enter)" : "";
        if (cfg.isCoreEntrySlowestChat()) {
            ModChat.send(CHAT, ModChat.text("Slowest into core: "), ModChat.value(slowest.getKey()),
                    ModChat.text(" ("), ModChat.value(format(slowest.getValue())), ModChat.text(")" + suffix));
        }
        if (cfg.isCoreEntrySlowestParty()) {
            Minecraft client = Minecraft.getInstance();
            if (client.player != null) {
                OutgoingChat.command("pc Slowest into core: " + slowest.getKey() + " (" + format(slowest.getValue()) + ")");
            }
        }
    }

    /** For the Split Timers HUD's own bottom "Slowest Into Core" line (killer560, 2026-09-20: "at the very
     *  bottom of the split timers show the slowest person into core and their time") - independent of the
     *  chat/party toggles and of {@link #announced}, so it can show live (whoever is slowest so far) while
     *  the run is still going, using the exact same data {@link #announce()} does. Null before any entry. */
    static String slowestHudLine() {
        if (openedMs == 0L) {
            return null;
        }
        Map.Entry<String, Long> slowest = slowestEntry();
        if (slowest == null) {
            return null;
        }
        return colorFor(slowest.getKey()) + slowest.getKey() + "§f: " + format(slowest.getValue());
    }
}
