package com.killer560.hub.secretwaypoints;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.BoundedDownload;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.ModNet;
import com.killer560.hub.util.ModPaths;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;

import java.io.InputStream;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Community secret routes: premade per-room routes read at runtime, never shipped in the jar. The source is the
 * player's own route folder when one exists (a matching install's {@code routes} folder under the game's config folder, read in place),
 * else the public file downloaded through {@link ModNet} service {@code community-routes} into a per-instance cache
 * ({@code killer560smod-communityroutes.json}, kept out of profiles), refreshed at most once a day. Loading runs on
 * one background thread; the tick only asks {@link #forRoom}.
 *
 * <p>The file maps "Room-Name-N" (N is the room's secret count) to an ordered list of steps, one per secret, each
 * with {@code locations} (the walked path), {@code etherwarps}, {@code mines}, {@code interacts} and a
 * {@code secret}; every coordinate is in the room's own frame (x/z from the room corner at rotation 0, y absolute),
 * the same frame as the room database, so {@link com.killer560.hub.roomdatabase.RoomDatabase#toRealCoord} places it.
 */
public final class CommunityRoutes {

    /** One secret's leg of a room route, all coordinates room-relative. */
    public record Step(int[][] path, int[][] etherwarps, int[][] mines, int[][] interacts, int[] secret) {
    }

    private static final Logger LOGGER = ModLog.get("killer560smod-secretwaypoints");
    private static final String DEFAULT_URL = "https://raw.githubusercontent.com/yourboykyle/SecretRoutes/main/routes.json";
    private static final String[] LOCAL_FILES = {"routes.json", "3ppopkaroutes.json", "fowroutes.json", "pearlroutes.json"};
    private static final long MAX_BYTES = 4L * 1024 * 1024;
    private static final long REFRESH_MS = 24L * 60 * 60 * 1000;
    private static final long RETRY_MS = 10L * 60 * 1000;
    /** Names the two files spell differently once punctuation is gone. */
    private static final Map<String, String> ALIASES = Map.of("dinodigsite", "dinosite", "withermancers", "withermancer");

    private static volatile Map<String, List<Step>> byRoom = Map.of();
    private static final AtomicBoolean loading = new AtomicBoolean(false);
    private static volatile boolean started;
    private static volatile long nextAttemptMs;

    private CommunityRoutes() {
    }

    /** Steps for one of our room names (e.g. "Dino Site"), or null. Starts the one-time load on first use. */
    public static List<Step> forRoom(String roomName) {
        if (!started) ensureLoading();
        return roomName == null ? null : byRoom.get(key(roomName));
    }

    /** How many rooms are loaded (test hook). */
    public static int roomCountForTest() { return byRoom.size(); }

    /** Replaces the routes with the ones in {@code json}, as the loader would (test hook). */
    public static void loadForTest(String json) {
        started = true;
        byRoom = parse(json);
    }

    /** Drops the cache and the loaded routes and loads again on this thread, through the service override (test hook). */
    public static int reloadForTest() throws Exception {
        started = true;
        Files.deleteIfExists(ModPaths.config("killer560smod-communityroutes.json"));
        load();
        return byRoom.size();
    }

    private static void ensureLoading() {
        if (System.currentTimeMillis() < nextAttemptMs || !loading.compareAndSet(false, true)) return;
        started = true;
        Thread t = new Thread(CommunityRoutes::load, "killer560smod-communityroutes");
        t.setDaemon(true);
        t.start();
    }

    private static void load() {
        try {
            Map<String, List<Step>> loaded = Map.of();
            Path own = FabricLoader.getInstance().getGameDir().resolve("config").resolve("SecretRoutes").resolve("routes");
            for (String f : LOCAL_FILES) {
                if (loaded.isEmpty() && Files.isRegularFile(own.resolve(f)) && Files.size(own.resolve(f)) <= MAX_BYTES) {
                    loaded = parse(Files.readString(own.resolve(f), StandardCharsets.UTF_8));
                }
            }
            if (loaded.isEmpty()) loaded = fromCacheOrNetwork();
            byRoom = loaded;
            if (loaded.isEmpty()) nextAttemptMs = System.currentTimeMillis() + RETRY_MS;
            LOGGER.info("[CommunityRoutes] {} rooms loaded", loaded.size());
        } catch (Exception e) {
            nextAttemptMs = System.currentTimeMillis() + RETRY_MS;
            LOGGER.warn("[CommunityRoutes] load failed: {}", e.toString());
        } finally {
            loading.set(false);
        }
    }

    private static Map<String, List<Step>> fromCacheOrNetwork() throws Exception {
        Path cache = ModPaths.config("killer560smod-communityroutes.json");
        boolean fresh = Files.isRegularFile(cache)
                && System.currentTimeMillis() - Files.getLastModifiedTime(cache).toMillis() < REFRESH_MS;
        if (!fresh) {
            try {
                URLConnection conn = ModNet.open(ModNet.url("community-routes", DEFAULT_URL));
                conn.setConnectTimeout(10_000);
                conn.setReadTimeout(30_000);
                Files.createDirectories(cache.getParent());
                Path tmp = cache.resolveSibling(cache.getFileName() + ".tmp");
                try (InputStream in = conn.getInputStream()) {
                    BoundedDownload.toFile(in, tmp, MAX_BYTES, "community routes");
                }
                if (parse(Files.readString(tmp, StandardCharsets.UTF_8)).isEmpty()) {
                    Files.deleteIfExists(tmp);
                } else {
                    Files.move(tmp, cache, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (Exception e) {
                LOGGER.warn("[CommunityRoutes] download failed ({}), using the cached copy if any", e.toString());
            }
        }
        return Files.isRegularFile(cache) ? parse(Files.readString(cache, StandardCharsets.UTF_8)) : Map.of();
    }

    /** Lower-case letters and digits only, with a trailing "-N" secret count and a ":variant" tail dropped. */
    static String key(String name) {
        String n = name;
        int colon = n.indexOf(':');
        if (colon >= 0) n = n.substring(0, colon);
        n = n.replaceFirst("-\\d+$", "").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return ALIASES.getOrDefault(n, n);
    }

    static Map<String, List<Step>> parse(String json) {
        Map<String, List<Step>> out = new HashMap<>();
        try {
            for (Map.Entry<String, JsonElement> e : JsonParser.parseString(json).getAsJsonObject().entrySet()) {
                if (e.getKey().startsWith("#") || !e.getValue().isJsonArray()) continue;
                List<Step> steps = new ArrayList<>();
                for (JsonElement s : e.getValue().getAsJsonArray()) {
                    if (s.isJsonObject()) steps.add(step(s.getAsJsonObject()));
                }
                if (!steps.isEmpty()) out.putIfAbsent(key(e.getKey()), List.copyOf(steps));
            }
        } catch (Exception ex) {
            LOGGER.warn("[CommunityRoutes] unreadable route data: {}", ex.toString());
            return Map.of();
        }
        return Map.copyOf(out);
    }

    private static Step step(JsonObject o) {
        int[] secret = o.has("secret") && o.get("secret").isJsonObject() ? point(o.getAsJsonObject("secret").get("location")) : null;
        return new Step(points(o.get("locations")), points(o.get("etherwarps")), points(o.get("mines")),
                points(o.get("interacts")), secret);
    }

    private static int[][] points(JsonElement e) {
        if (e == null || !e.isJsonArray()) return new int[0][];
        List<int[]> list = new ArrayList<>();
        for (JsonElement p : e.getAsJsonArray()) {
            int[] pt = point(p);
            if (pt != null) list.add(pt);
        }
        return list.toArray(new int[0][]);
    }

    private static int[] point(JsonElement e) {
        if (e == null || !e.isJsonArray()) return null;
        JsonArray a = e.getAsJsonArray();
        if (a.size() < 3) return null;
        try {
            return new int[]{a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt()};
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
