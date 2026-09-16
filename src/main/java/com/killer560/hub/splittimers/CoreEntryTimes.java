package com.killer560.hub.splittimers;

import com.killer560.hub.dungeonclass.DungeonClass;
import com.killer560.hub.leapmenu.LeapMenuFeature;
import com.killer560.hub.leapmenu.PartyTracker;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.ModChat;
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
 * <b>Entry detection:</b> Hypixel never announces who entered the core, so entry is a position crossing. The core
 * (Necron's arena) is the P4 band of the F7/M7 boss room: {@code 45 &lt; y &lt;= 100}. That band is not invented here -
 * it is this repo's own {@code fastleap.Floor7Tracker.getPhaseAt()} ({@code y > 210} P1, {@code > 155} P2,
 * {@code > 100} P3, {@code > 45} P4, else P5), ported from QUOI {@code enums/Phase.kt}, and it is confirmed
 * independently by NoammAddons {@code utils/location/LocationUtils.kt}'s {@code getPhase(y)}, which uses the exact
 * same five thresholds. {@code Floor7Tracker.getPhaseAt()} only reads the local player, so the same band is applied
 * by hand to each teammate's own Y.
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

    /** Floor7Tracker / NoammAddons LocationUtils: P4 (the core) is 45 &lt; y &lt;= 100. */
    private static final double CORE_MAX_Y = 100.0;
    private static final double CORE_MIN_Y = 45.0;
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
        SplitTimersFeature.LOGGER.info("[SplitTimers] Core entry timing started for {}", EXPECTED);
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
        if (openedMs == 0L || !SplitTimersConfig.getInstance().isCoreEntryTimes()) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) {
            return;
        }
        long now = System.currentTimeMillis();
        record(client.player, now);
        for (Player player : LeapMenuFeature.currentPartyMembers()) {
            if (player.getUUID().version() != 4) {
                continue; // Hypixel NPCs / display entities are v2 UUIDs - same filter Class Colors uses.
            }
            record(player, now);
        }
        if (!announced && ENTRIES.size() >= EXPECTED.size() && !EXPECTED.isEmpty()) {
            announce();
        }
    }

    private static void record(Player player, long now) {
        String name = player.getGameProfile().name();
        if (ENTRIES.containsKey(name)) {
            return;
        }
        double y = player.getY();
        if (y > CORE_MAX_Y || y <= CORE_MIN_Y) {
            return;
        }
        if (!EXPECTED.contains(name)) {
            // The party list wasn't complete when the core opened (tab list not parsed yet, late join) - they are
            // clearly in this run, so they join the expected list instead of being timed but never shown.
            EXPECTED.add(name);
        }
        ENTRIES.put(name, now - openedMs);
        SplitTimersFeature.LOGGER.info("[SplitTimers] Core entry: {} {}ms after the core opened", name, now - openedMs);
    }

    /** HUD/chat rows: everyone expected, in party order, with a dash for anyone never seen inside the core. */
    static List<String> lines() {
        if (openedMs == 0L || EXPECTED.isEmpty() || !SplitTimersConfig.getInstance().isCoreEntryTimes()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(EXPECTED.size());
        for (String name : EXPECTED) {
            Long ms = ENTRIES.get(name);
            out.add(colorFor(name) + name + "§f: " + (ms == null ? "§7-" : format(ms)));
        }
        return out;
    }

    /** Sample rows for the HUD editor, so the block can be positioned outside a run. */
    static List<String> editorLines() {
        return List.of("§6§lCore Entry", "§bYou§f: 2.35s", "§aTeammate§f: 4.10s", "§cTeammate§f: §7-");
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
        return String.format(Locale.US, "%.2fs", ms / 1000.0);
    }

    /** Slowest player into the core, once per run, to Mod Chat and/or party chat - both off by default. */
    private static void announce() {
        if (announced || openedMs == 0L) {
            return;
        }
        announced = true;
        SplitTimersConfig cfg = SplitTimersConfig.getInstance();
        if (!cfg.isCoreEntryTimes() || (!cfg.isCoreEntrySlowestChat() && !cfg.isCoreEntrySlowestParty())) {
            return;
        }
        String slowest = null;
        long worst = -1L;
        for (Map.Entry<String, Long> entry : ENTRIES.entrySet()) {
            if (entry.getValue() > worst) {
                worst = entry.getValue();
                slowest = entry.getKey();
            }
        }
        if (slowest == null) {
            return;
        }
        int missing = EXPECTED.size() - ENTRIES.size();
        String suffix = missing > 0 ? " (" + missing + " never seen entering)" : "";
        if (cfg.isCoreEntrySlowestChat()) {
            ModChat.send(CHAT, ModChat.text("Slowest into core: "), ModChat.value(slowest),
                    ModChat.text(" ("), ModChat.value(format(worst)), ModChat.text(")" + suffix));
        }
        if (cfg.isCoreEntrySlowestParty()) {
            Minecraft client = Minecraft.getInstance();
            if (client.player != null) {
                // Same channel command the Chat Commands feature uses for its party replies.
                client.player.connection.sendCommand("pc Slowest into core: " + slowest + " (" + format(worst) + ")");
            }
        }
    }
}
