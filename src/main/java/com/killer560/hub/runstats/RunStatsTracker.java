package com.killer560.hub.runstats;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.killer560.hub.dungeonclass.DungeonClass;
import com.killer560.hub.leapmenu.PartyTracker;
import com.killer560.hub.livemap.RunStatsBridge;
import com.killer560.hub.profileviewer.api.ProfileViewerApi;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * <li><b>Secrets</b> - also not in the chat page. Taken as the delta of each player's lifetime secret count
 *     ({@code achievements.skyblock_treasure_hunter} in Hypixel's player data) between run start and run end,
 *     which is how NoammAddons does it too ({@code ProfileUtils.getSecrets} against their own API). Off by
 *     default because it hits the network, and it needs Hypixel's player data to have caught up with the run.
 * <li><b>Deaths</b> - real per-player data, straight from the run's "{@code ☠}" chat lines.
 * </ul>
 */
public final class RunStatsTracker {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-runstats");

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

    private static volatile List<PlayerRunStats> lastRun = List.of();
    private static boolean snapshotTaken = false;

    private RunStatsTracker() {
    }

    // ------------------------------------------------------------------------------------- run lifecycle

    /** Clears everything for a fresh run (world change / dungeon entry). */
    public static synchronized void resetRun() {
        SOLO_ROOMS.clear();
        STACKED_ROOMS.clear();
        DEATHS.clear();
        DISPLAY_NAMES.clear();
        SECRETS_BEFORE.clear();
        SECRETS_AFTER.clear();
        snapshotTaken = false;
        RunStatsBridge.reset();
    }

    public static boolean snapshotTaken() {
        return snapshotTaken;
    }

    /** Records everyone's lifetime secret count at the start of the run. No-op if secrets are off. */
    public static void snapshotSecrets(Minecraft client) {
        snapshotTaken = true;
        if (!RunStatsConfig.getInstance().isFetchSecrets()) {
            return;
        }
        for (String name : partyNames(client)) {
            fetchSecrets(name, SECRETS_BEFORE);
        }
    }

    /** Records everyone's lifetime secret count at the end of the run (cache bypassed). */
    public static void captureSecrets(Minecraft client) {
        if (!RunStatsConfig.getInstance().isFetchSecrets()) {
            return;
        }
        for (String name : partyNames(client)) {
            fetchSecrets(name, SECRETS_AFTER);
        }
    }

    // ------------------------------------------------------------------------------------- collection

    /** Feeds the map-derived room clears in. Call every few ticks while in a dungeon. */
    public static synchronized void pollRooms(Minecraft client) {
        if (!RunStatsConfig.getInstance().isTrackRooms()) {
            return;
        }
        for (RunStatsBridge.Clear clear : RunStatsBridge.pollClears(client)) {
            List<String> inside = clear.players();
            if (inside.isEmpty()) {
                continue;
            }
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
        Matcher m = DEATH.matcher(plain);
        if (!m.matches()) {
            return;
        }
        String name = m.group(1);
        if (name == null) {
            name = client.player == null ? null : client.player.getGameProfile().name();
        }
        if (name == null) {
            return;
        }
        remember(name);
        DEATHS.computeIfAbsent(key(name), k -> new ArrayList<>()).add(m.group(2));
    }

    // ------------------------------------------------------------------------------------- results

    /** The rows of the most recent finished run, in party order. Consume this from {@code runsummary}. */
    public static List<PlayerRunStats> lastRun() {
        return lastRun;
    }

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

    // ------------------------------------------------------------------------------------- internals

    private static Set<String> rooms(Map<String, Set<String>> map, String name) {
        remember(name);
        return map.computeIfAbsent(key(name), k -> new LinkedHashSet<>());
    }

    private static void remember(String name) {
        DISPLAY_NAMES.putIfAbsent(key(name), name);
    }

    private static String displayName(String name) {
        return DISPLAY_NAMES.getOrDefault(key(name), name);
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.US);
    }

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
        ProfileViewerApi.resolve(name)
                .thenCompose(player -> ProfileViewerApi.fetchAux(ProfileViewerApi.AuxKind.PLAYER,
                        player.uuid().toString(), true))
                .thenAccept(aux -> {
                    Long secrets = treasureHunter(aux.data());
                    if (secrets != null) {
                        into.put(key, secrets);
                    }
                })
                .exceptionally(t -> {
                    LOGGER.info("[RunStats] No secret count for {}: {}", name, ProfileViewerApi.messageFor(t));
                    return null;
                });
    }

    /** Hypixel's lifetime secret counter: {@code player.achievements.skyblock_treasure_hunter}. */
    private static Long treasureHunter(JsonObject player) {
        if (player == null) {
            return null;
        }
        JsonElement achievements = player.get("achievements");
        if (achievements == null || !achievements.isJsonObject()) {
            return null;
        }
        JsonElement value = achievements.getAsJsonObject().get("skyblock_treasure_hunter");
        if (value == null || !value.isJsonPrimitive()) {
            return null;
        }
        try {
            return value.getAsLong();
        } catch (Exception e) {
            return null;
        }
    }
}
