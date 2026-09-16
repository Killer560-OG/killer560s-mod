package com.killer560.hub.pathfinding;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Downloads and caches the island navigation graphs at runtime, the same way {@code roomdatabase/RoomDatabase} handles
 * the dungeon room database: nothing is bundled in this repo, the files live in the config folder
 * ({@code config/killer560smod-pathfinding/graphs/}), a failed fetch backs off instead of retrying every tick, and a
 * previously downloaded copy keeps working while the network is down.
 * <p>
 * Source: SkyHanni's own public data repo, {@code constants/island_graphs/<ISLAND>.json} in
 * https://github.com/hannibal002/SkyHanni-REPO (MIT licensed). That is the exact same file SkyHanni's own client
 * downloads, so the data stays current instead of going stale in a bundled copy. Pinned to the {@code main} branch
 * (SkyHanni's clients read that branch too); the jsDelivr CDN mirror of the same branch is the fallback when
 * raw.githubusercontent.com is unreachable.
 */
public final class GraphRepository {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-pathfinding");

    private static final String BRANCH = "main";
    private static final String PRIMARY = "https://raw.githubusercontent.com/hannibal002/SkyHanni-REPO/" + BRANCH
            + "/constants/island_graphs/%s.json";
    private static final String FALLBACK = "https://cdn.jsdelivr.net/gh/hannibal002/SkyHanni-REPO@" + BRANCH
            + "/constants/island_graphs/%s.json";

    /** Re-check GitHub for a newer copy at most this often (the graphs change a few times a week at most). */
    private static final long REFRESH_AFTER_MS = 12 * 60 * 60 * 1000L;
    private static final long BASE_RETRY_BACKOFF_MS = 30_000L;
    private static final long MAX_RETRY_BACKOFF_MS = 10 * 60_000L;
    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 20_000;
    /** Hard cap on one graph download (the largest real file is ~2 MB). */
    private static final int MAX_DOWNLOAD_BYTES = 16 * 1024 * 1024;

    /** Islands that exist as graph files (see the SkyHanni-REPO folder listing). */
    public static final Set<String> KNOWN_ISLANDS = Set.of(
            "BACKWATER_BAYOU", "CRIMSON_ISLE", "CRYSTAL_HOLLOWS", "DEEP_CAVERNS", "DUNGEON_HUB", "DWARVEN_MINES",
            "GALATEA", "GLACITE_TUNNELS", "GOLD_MINES", "HUB", "LOTUS_ATOLL", "SAFARI", "SPIDER_DEN", "THE_END",
            "THE_FARMING_ISLANDS", "THE_PARK", "THE_RIFT", "TORRHUS_CANYON", "WINTER");

    private static final Map<String, IslandGraph> LOADED = new ConcurrentHashMap<>();
    private static final Set<String> LOADING = ConcurrentHashMap.newKeySet();
    private static final Map<String, Long> NEXT_ATTEMPT = new ConcurrentHashMap<>();
    private static final Map<String, String> LAST_ERROR = new ConcurrentHashMap<>();
    private static final Map<String, Integer> FAILURES = new ConcurrentHashMap<>();

    private GraphRepository() {
    }

    public static Path dataDir() {
        return FabricLoader.getInstance().getConfigDir().resolve("killer560smod-pathfinding").resolve("graphs");
    }

    /** The graph for this island if it is already loaded, else null (a background load is started). */
    public static IslandGraph get(String island) {
        if (island == null || !KNOWN_ISLANDS.contains(island)) {
            return null;
        }
        IslandGraph graph = LOADED.get(island);
        if (graph == null) {
            ensureLoading(island, false);
        }
        return graph;
    }

    public static boolean isLoaded(String island) {
        return island != null && LOADED.containsKey(island);
    }

    public static String lastError(String island) {
        return LAST_ERROR.get(island);
    }

    /** Safe to call every tick: no-ops once loaded, at most one load per island in flight, backs off on failure. */
    public static void ensureLoading(String island, boolean force) {
        if (island == null || !KNOWN_ISLANDS.contains(island)) {
            return;
        }
        if (!force && LOADED.containsKey(island)) {
            return;
        }
        Long next = NEXT_ATTEMPT.get(island);
        if (!force && next != null && System.currentTimeMillis() < next) {
            return;
        }
        if (!LOADING.add(island)) {
            return;
        }
        Thread thread = new Thread(() -> {
            try {
                load(island, force);
            } finally {
                LOADING.remove(island);
            }
        }, "killer560smod-graph-" + island);
        thread.setDaemon(true);
        thread.start();
    }

    /** Forgets every cached graph and re-downloads the current island's ({@code /k560path reload}). */
    public static void forceReload(String island) {
        LOADED.remove(island);
        NEXT_ATTEMPT.remove(island);
        FAILURES.remove(island);
        ensureLoading(island, true);
    }

    private static void load(String island, boolean force) {
        Path dir = dataDir();
        Path file = dir.resolve(island + ".json");
        boolean haveCache = Files.exists(file);
        if (haveCache && !force) {
            try {
                IslandGraph graph = IslandGraph.parse(island, Files.readString(file, StandardCharsets.UTF_8));
                LOADED.put(island, graph);
                LOGGER.info("[Pathfinding] Loaded cached graph for {} ({} nodes)", island, graph.nodes.length);
            } catch (Exception e) {
                LOGGER.warn("[Pathfinding] Cached graph for {} is unusable ({}) - re-downloading", island, e.toString());
                haveCache = false;
            }
        }
        long age = Long.MAX_VALUE;
        try {
            if (haveCache) {
                age = System.currentTimeMillis() - Files.getLastModifiedTime(file).toMillis();
            }
        } catch (Exception ignored) {
            // treated as "old enough to refresh"
        }
        if (haveCache && !force && age < REFRESH_AFTER_MS) {
            FAILURES.remove(island);
            return;
        }

        String json = null;
        String error = null;
        for (String template : new String[]{PRIMARY, FALLBACK}) {
            try {
                json = fetch(String.format(template, island));
                error = null;
                break;
            } catch (Exception e) {
                error = e.toString();
            }
        }
        if (json != null) {
            try {
                IslandGraph graph = IslandGraph.parse(island, json);
                Files.createDirectories(dir);
                Path tmp = dir.resolve(island + ".json.tmp");
                Files.writeString(tmp, json, StandardCharsets.UTF_8);
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                LOADED.put(island, graph);
                FAILURES.remove(island);
                NEXT_ATTEMPT.remove(island);
                LAST_ERROR.remove(island);
                LOGGER.info("[Pathfinding] Downloaded graph for {} ({} nodes)", island, graph.nodes.length);
                return;
            } catch (Exception e) {
                error = "bad data: " + e;
            }
        }

        LAST_ERROR.put(island, error);
        int failures = FAILURES.merge(island, 1, Integer::sum);
        long backoff = Math.min(MAX_RETRY_BACKOFF_MS, BASE_RETRY_BACKOFF_MS << Math.min(failures - 1, 5));
        NEXT_ATTEMPT.put(island, System.currentTimeMillis() + backoff);
        if (LOADED.containsKey(island)) {
            LOGGER.warn("[Pathfinding] Graph refresh for {} failed ({}) - keeping the cached copy, retrying in {}s",
                    island, error, backoff / 1000);
        } else {
            LOGGER.warn("[Pathfinding] Graph download for {} failed ({}) - retrying in {}s", island, error, backoff / 1000);
        }
    }

    private static String fetch(String url) throws Exception {
        URL target = URI.create(url).toURL();
        URLConnection connection = target.openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestProperty("User-Agent", "killer560smod/pathfinding");
        try (InputStream in = connection.getInputStream()) {
            // Never read an unbounded body into memory: the biggest real graph file is a couple of MB, so anything
            // past the cap is a broken mirror / hostile response and is treated as a failed fetch (it backs off).
            byte[] body = in.readNBytes(MAX_DOWNLOAD_BYTES + 1);
            if (body.length > MAX_DOWNLOAD_BYTES) {
                throw new java.io.IOException("graph response larger than " + MAX_DOWNLOAD_BYTES + " bytes");
            }
            return new String(body, StandardCharsets.UTF_8);
        }
    }
}
