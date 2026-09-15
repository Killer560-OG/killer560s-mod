package com.killer560.hub.storagesearch;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** "Cache last updated" times per Storage Overlay cache key. {@code StorageOverlayCache} itself stores no
 *  timestamps (and is read-only from this package), so this records the wall-clock time a tracked
 *  Ender Chest page / Backpack screen was last closed - that's when the overlay's capture of it was last
 *  refreshed. Kept in its own file so the overlay's cache format stays untouched. A key with no entry here
 *  (logged before this feature existed) falls back to the cache file's own modified time, shown as
 *  "before" that time so it never claims to be fresher than it is. */
public final class StorageSearchTimestamps {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-storagesearch-timestamps.json");
    private static final Path OVERLAY_CACHE_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-storageoverlay-cache.json");

    private static final Map<String, Long> TIMES = new HashMap<>();
    private static boolean loaded = false;

    private StorageSearchTimestamps() {
    }

    private static void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        if (!Files.exists(PATH)) {
            return;
        }
        try {
            JsonObject obj = JsonParser.parseString(Files.readString(PATH, StandardCharsets.UTF_8)).getAsJsonObject();
            for (String key : obj.keySet()) {
                TIMES.put(key, obj.get(key).getAsLong());
            }
        } catch (Exception ignored) {
        }
    }

    public static void mark(String key, long timeMs) {
        ensureLoaded();
        TIMES.put(key, timeMs);
        try {
            Files.createDirectories(PATH.getParent());
            JsonObject obj = new JsonObject();
            for (Map.Entry<String, Long> e : TIMES.entrySet()) {
                obj.addProperty(e.getKey(), e.getValue());
            }
            Files.writeString(PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    /** @return the recorded time for {@code key}, or -1 if none recorded. */
    public static long get(String key) {
        ensureLoaded();
        Long t = TIMES.get(key);
        return t == null ? -1L : t;
    }

    /** @return the Storage Overlay cache file's last-modified time, or -1 if it doesn't exist. */
    public static long overlayCacheFileTime() {
        try {
            return Files.exists(OVERLAY_CACHE_PATH) ? Files.getLastModifiedTime(OVERLAY_CACHE_PATH).toMillis() : -1L;
        } catch (Exception e) {
            return -1L;
        }
    }

    /** "just now" / "5m ago" / "3h ago" / "2d ago". */
    public static String ago(long timeMs) {
        long diff = Math.max(0, System.currentTimeMillis() - timeMs);
        long min = diff / 60_000L;
        if (min < 1) {
            return "just now";
        }
        if (min < 60) {
            return min + "m ago";
        }
        long hours = min / 60;
        if (hours < 48) {
            return hours + "h ago";
        }
        return (hours / 24) + "d ago";
    }
}
