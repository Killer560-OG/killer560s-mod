package com.killer560.hub.runstats;

import com.killer560.hub.dungeonclass.DungeonClass;
import com.killer560.hub.leapmenu.PartyTracker;
import com.killer560.hub.livemap.RunStatsBridge;
import com.killer560.hub.profileviewer.api.ProfileViewerApi;
import com.killer560.hub.profileviewer.data.SbProfile;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import com.killer560.hub.util.ModLog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Collects the per-player numbers for one dungeon run.
 *
 * <p><b>What Hypixel actually gives you.</b> The end-of-run chat block (the "{@code > EXTRA STATS <}" page, and
 * what {@code /showextrastats} re-prints) contains <i>no per-player stats at all</i> - on current Hypixel it is
 * the floor header, Team Score, the "Defeated ... in ..." line, then bits and class experience. Verified against
 * this machine's own dungeon logs (see the feature's README note). The only per-player things the client sees
 * are: the tab list (class + level + who is dead), the death messages, and the dungeon map item. So:
 *
 * <ul>
 * <li><b>Rooms cleared</b> - derived, not given. A room that flips to cleared/green on the map is credited to
 *     whoever is standing in it at that moment ({@link RunStatsBridge}). One player inside -> a certain ("solo")
 *     clear; several inside -> a "stacked" clear that counts as <i>possible</i> for each of them, which is why
 *     the summary prints a range like {@code 4-7}. This is the same mechanism NoammAddons uses
 *     ({@code ClearInfoUpdater.checkSplits}); rooms cleared while the room is off the map's discovered set, or
 *     while the clearer has already walked out, are missed.
 * <li><b>Secrets</b> - the chat page has only the team total ("Secrets Found: N", {@link #teamSecrets()}). Per player
 *     it is the delta of the lifetime secret count in the selected SkyBlock profile ({@code dungeons.secrets}, the
 *     profiles feed Party Finder already reads; the SkyBlockPV backend's /player feed carries no achievements) between
 *     run start and run end. Off by default because it hits the network; the API lags the run, so the end count is
 *     asked again until the deltas add up to the team total.
 * <li><b>Deaths</b> - real per-player data, straight from the run's "{@code ☠}" chat lines.
 * </ul>
 */
public final class RunStatsTracker {

    private static final Logger LOGGER = ModLog.get("killer560smod-runstats");

    /** NoammAddons {@code DungeonListener.deathRegex}. */
    private static final Pattern DEATH = Pattern.compile(
            "^\\s*☠ (?:You were|(\\w{1,16})) (.+?)(?: and became a ghost)?\\.$");

    /** Certain clears (alone in the room) and possible clears (stacked), by lower-case name. */
    private static final Map<String, Set<String>> SOLO_ROOMS = new LinkedHashMap<>();
    private static final Map<String, Set<String>> STACKED_ROOMS = new LinkedHashMap<>();
    private static final Map<String, List<String>> DEATHS = new LinkedHashMap<>();
    private static final Map<String, String> DISPLAY_NAMES = new LinkedHashMap<>();
    private static final Map<String, Long> SECRETS_BEFORE = new ConcurrentHashMap<>();
    private static final Map<String, Long> SECRETS_AFTER = new ConcurrentHashMap<>();

    /** Lookups asked per name (start of run / end of run), so a failed one is retried a few times and no more. */
    private static final Map<String, Integer> BEFORE_TRIES = new ConcurrentHashMap<>();
    private static final Set<String> IN_FLIGHT = ConcurrentHashMap.newKeySet();
    private static final Pattern TEAM_SECRETS = Pattern.compile("^Secrets Found: (\\d{1,6})$");

    private static volatile List<PlayerRunStats> lastRun = List.of();
    private static volatile int teamSecrets = -1;
    /** Bumped by every {@link #resetRun}; a secrets lookup that finishes after a reset belongs to the old run. */
    private static volatile int runGeneration = 0;

    private RunStatsTracker() {
    }


    /** Clears everything for a fresh run (world change / dungeon entry). */
    public static synchronized void resetRun() {
        SOLO_ROOMS.clear();
        STACKED_ROOMS.clear();
        DEATHS.clear();
        DISPLAY_NAMES.clear();
        SECRETS_BEFORE.clear();
        SECRETS_AFTER.clear();
        BEFORE_TRIES.clear();
        IN_FLIGHT.clear();
        teamSecrets = -1;
        runGeneration++;
        RunStatsBridge.reset();
    }

    /** The whole team's "Secrets Found" from the end-of-run block, or -1 before it was read. */
    public static int teamSecrets() { return teamSecrets; }

    /** Asks for the start-of-run count of everyone still without one (the party may fill in after entry). Call every few seconds. */
    public static void snapshotSecrets(Minecraft client) {
        if (!RunStatsConfig.getInstance().isFetchSecrets()) return;
        for (String name : partyNames(client)) {
            String key = key(name);
            if (SECRETS_BEFORE.containsKey(key) || BEFORE_TRIES.merge(key, 1, Integer::sum) > 4) continue;
            fetchSecrets(name, SECRETS_BEFORE);
        }
    }

    /** Asks for the end-of-run count of everyone with a start count (cache bypassed). */
    public static void captureSecrets(Minecraft client) {
        if (!RunStatsConfig.getInstance().isFetchSecrets()) return;
        for (String name : partyNames(client)) {
            if (SECRETS_BEFORE.containsKey(key(name))) fetchSecrets(name, SECRETS_AFTER);
        }
    }

    /** True while a lookup is out, or the deltas still fall short of the team total (the API lags the run). */
    public static boolean secretsPending() {
        if (!RunStatsConfig.getInstance().isFetchSecrets() || SECRETS_BEFORE.isEmpty()) return false;
        if (!IN_FLIGHT.isEmpty()) return true;
        long sum = 0;
        for (Map.Entry<String, Long> e : SECRETS_BEFORE.entrySet()) {
            Long after = SECRETS_AFTER.get(e.getKey());
            if (after == null) return true;
            sum += Math.max(0, after - e.getValue());
        }
        return teamSecrets >= 0 && sum < teamSecrets;
    }


    /** Feeds the map-derived room clears in. Call every few ticks while in a dungeon. */
    public static synchronized void pollRooms(Minecraft client) {
        if (!RunStatsConfig.getInstance().isTrackRooms()) return;
        for (RunStatsBridge.Clear clear : RunStatsBridge.pollClears(client)) {
            List<String> inside = clear.players();
            if (inside.isEmpty()) continue;
            if (inside.size() == 1) {
                rooms(SOLO_ROOMS, inside.get(0)).add(clear.roomName());
            } else {
                for (String name : inside) {
                    rooms(STACKED_ROOMS, name).add(clear.roomName());
                }
            }
        }
    }

    /** Feeds one stripped chat line in; picks up the run's death messages. */
    public static synchronized void onChatLine(Minecraft client, String plain) {
        Matcher ts = TEAM_SECRETS.matcher(plain);
        if (ts.matches()) {
            teamSecrets = Integer.parseInt(ts.group(1));
            return;
        }
        Matcher m = DEATH.matcher(plain);
        if (!m.matches()) return;
        String name = m.group(1);
        if (name == null) {
            name = client.player == null ? null : client.player.getGameProfile().name();
        }
        if (name == null) return;
        remember(name);
        DEATHS.computeIfAbsent(key(name), k -> new ArrayList<>()).add(m.group(2));
    }


    /** The rows of the most recent finished run, in party order. Consume this from {@code runsummary}. */
    public static List<PlayerRunStats> lastRun() { return lastRun; }

    /** Builds (and remembers) the rows for the run that just ended. */
    public static synchronized List<PlayerRunStats> buildRows(Minecraft client) {
        boolean rooms = RunStatsConfig.getInstance().isTrackRooms();
        List<PlayerRunStats> out = new ArrayList<>();
        for (String name : partyNames(client)) {
            String key = key(name);
            Set<String> solo = SOLO_ROOMS.get(key);
            Set<String> stacked = STACKED_ROOMS.get(key);
            List<String> deaths = DEATHS.getOrDefault(key, List.of());
            DungeonClass cls = PartyTracker.classOf(name);
            int secrets = -1;
            Long before = SECRETS_BEFORE.get(key);
            Long after = SECRETS_AFTER.get(key);
            if (before != null && after != null && after >= before) {
                secrets = (int) Math.min(Integer.MAX_VALUE, after - before);
            }
            out.add(new PlayerRunStats(displayName(name), cls,
                    rooms ? (solo == null ? 0 : solo.size()) : -1,
                    rooms ? (stacked == null ? 0 : stacked.size()) : -1,
                    secrets, deaths));
        }
        lastRun = List.copyOf(out);
        return lastRun;
    }

    /** Room names behind a player's counts, for the hover tooltip: {@code [soloRooms, stackedRooms]}. */
    public static synchronized List<List<String>> roomBreakdown(String name) {
        String key = key(name);
        return List.of(new ArrayList<>(SOLO_ROOMS.getOrDefault(key, Set.of())),
                new ArrayList<>(STACKED_ROOMS.getOrDefault(key, Set.of())));
    }


    private static Set<String> rooms(Map<String, Set<String>> map, String name) {
        remember(name);
        return map.computeIfAbsent(key(name), k -> new LinkedHashSet<>());
    }

    private static void remember(String name) { DISPLAY_NAMES.putIfAbsent(key(name), name); }

    private static String displayName(String name) { return DISPLAY_NAMES.getOrDefault(key(name), name); }

    private static String key(String name) { return name.toLowerCase(Locale.US); }

    /** You first, then the party in Hypixel's listing order. */
    private static List<String> partyNames(Minecraft client) {
        List<String> names = new ArrayList<>();
        if (client.player != null) {
            names.add(client.player.getGameProfile().name());
        }
        for (String n : PartyTracker.teammates()) {
            if (!names.contains(n)) {
                names.add(n);
            }
        }
        if (names.size() <= 1) {
            // Solo run, or the tab list never showed the party: fall back to whoever we credited a room to.
            for (String key : DISPLAY_NAMES.keySet()) {
                String display = DISPLAY_NAMES.get(key);
                if (names.stream().noneMatch(n -> n.equalsIgnoreCase(display))) {
                    names.add(display);
                }
            }
        }
        return names;
    }

    private static void fetchSecrets(String name, Map<String, Long> into) {
        String key = key(name);
        String flight = key + (into == SECRETS_BEFORE ? "<" : ">");
        if (!IN_FLIGHT.add(flight)) return;
        int generation = runGeneration;
        ProfileViewerApi.resolve(name)
                .thenCompose(player -> ProfileViewerApi.fetchProfiles(player.uuid(), true))
                .thenAccept(res -> {
                    SbProfile p = SbProfile.dungeonProfile(res.profiles());
                    if (p != null && generation == runGeneration) {
                        into.put(key, p.dungeons.secrets());
                    }
                })
                .exceptionally(t -> {
                    LOGGER.info("[RunStats] No secret count for {}: {}", name, ProfileViewerApi.messageFor(t));
                    return null;
                })
                .whenComplete((v, t) -> IN_FLIGHT.remove(flight));
    }
}
