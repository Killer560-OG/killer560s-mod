package com.killer560.hub.partyfinder;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Batched player dungeon stats for the Party Finder tooltip - a port of Devonian's {@code api/dungeon/DungeonsApi.kt}:
 * names are queued, and every 5 seconds one request fetches every queued name that has no data younger than
 * 10 minutes from the same endpoint Devonian uses ({@code https://api.docilelm.top/v2/dungeons/name1,name2}).
 * Response: {@code {"result": {"name": {"success", "status", "data": {level, secrets, averageSecrets,
 * personal_best_normal, personal_best_master, ...}}}}}.
 * <p>
 * Difference from Devonian: a failed name is retried after {@link #FAILURE_RETRY_MS} rather than on every poll.
 */
public final class PartyFinderStatsApi {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-partyfinder");

    private static final String ENDPOINT = "https://api.docilelm.top/v2/dungeons/";
    private static final long POLL_SECONDS = 5L;
    private static final long CACHE_MS = 10 * 60 * 1000L;
    private static final long FAILURE_RETRY_MS = 60 * 1000L;
    private static final int MAX_NAMES_PER_REQUEST = 50;

    /** {@code pbNormal}/{@code pbMaster}: {@code {"s": {"floor_7": "4:12"}, "s_plus": {...}}}, may be null. */
    public record PlayerStats(double level, int secrets, double averageSecrets, JsonObject pbNormal,
                              JsonObject pbMaster, long fetchedAtMs) {
    }

    private static final Set<String> QUEUE = ConcurrentHashMap.newKeySet();
    private static final Map<String, PlayerStats> CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Long> FAILED = new ConcurrentHashMap<>();

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static ScheduledExecutorService executor;

    private PartyFinderStatsApi() {
    }

    public static PlayerStats get(String name) {
        return name == null ? null : CACHE.get(name.toLowerCase(Locale.ROOT));
    }

    public static void request(Collection<String> names) {
        long now = System.currentTimeMillis();
        boolean added = false;
        for (String raw : names) {
            String name = raw.toLowerCase(Locale.ROOT);
            PlayerStats cached = CACHE.get(name);
            if (cached != null && now - cached.fetchedAtMs() < CACHE_MS) {
                continue;
            }
            Long failedAt = FAILED.get(name);
            if (failedAt != null && now - failedAt < FAILURE_RETRY_MS) {
                continue;
            }
            added |= QUEUE.add(name);
        }
        if (added) {
            ensureStarted();
        }
    }

    private static synchronized void ensureStarted() {
        if (executor != null) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "killer560smod-partyfinder-stats");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleWithFixedDelay(PartyFinderStatsApi::poll, POLL_SECONDS, POLL_SECONDS, TimeUnit.SECONDS);
    }

    private static void poll() {
        if (QUEUE.isEmpty()) {
            return;
        }
        List<String> names = new ArrayList<>();
        try {
            for (String name : QUEUE) {
                if (names.size() >= MAX_NAMES_PER_REQUEST) {
                    break;
                }
                names.add(name);
            }
            names.forEach(QUEUE::remove);

            HttpRequest req = HttpRequest.newBuilder(URI.create(ENDPOINT + String.join(",", names)))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "Killer560sMod-PartyFinder/1.0")
                    .GET().build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            long now = System.currentTimeMillis();
            if (resp.statusCode() / 100 != 2) {
                names.forEach(n -> FAILED.put(n, now));
                LOGGER.info("[PartyFinder] Stats request failed: HTTP {}", resp.statusCode());
                return;
            }
            JsonObject result = ConfigJson.getObject(JsonParser.parseString(resp.body()).getAsJsonObject(), "result");
            if (result == null) {
                names.forEach(n -> FAILED.put(n, now));
                return;
            }
            for (Map.Entry<String, JsonElement> e : result.entrySet()) {
                String name = e.getKey().toLowerCase(Locale.ROOT);
                JsonObject entry = e.getValue().isJsonObject() ? e.getValue().getAsJsonObject() : null;
                JsonObject data = ConfigJson.getObject(entry, "data");
                if (!ConfigJson.getBool(entry, "success", false) || data == null) {
                    FAILED.put(name, now);
                    continue;
                }
                FAILED.remove(name);
                CACHE.put(name, new PlayerStats(
                        ConfigJson.getDouble(data, "level", 0.0),
                        ConfigJson.getInt(data, "secrets", 0),
                        ConfigJson.getDouble(data, "averageSecrets", 0.0),
                        ConfigJson.getObject(data, "personal_best_normal"),
                        ConfigJson.getObject(data, "personal_best_master"),
                        now));
            }
        } catch (Exception e) {
            long now = System.currentTimeMillis();
            names.forEach(n -> FAILED.put(n, now));
            LOGGER.info("[PartyFinder] Stats request error: {}", e.toString());
        }
    }
}
