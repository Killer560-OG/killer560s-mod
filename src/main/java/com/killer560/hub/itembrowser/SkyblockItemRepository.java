package com.killer560.hub.itembrowser;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Fetches and caches the real, complete Skyblock item catalog from Hypixel's own public
 * {@code /v2/resources/skyblock/items} endpoint - a real "resources" endpoint (unlike
 * {@code /v2/skyblock/profiles}), meaning it needs no API key at all, unauthenticated and rate-limit-
 * generous by design. Real response (verified live, 2026-09-14): 5,651 real items, each with a real
 * {@code material} (legacy Bukkit-style item id), and - for anything with a distinct real Hypixel
 * appearance - either a real {@code item_model} (a modern real item-model override the game already
 * renders correctly whenever Hypixel's own resource pack is loaded, i.e. whenever actually connected to
 * Hypixel) or a real {@code skin} (a real Mojang player-profile texture blob, for skull-based cosmetics).
 * <p>
 * Cached to disk (just the small fields this mod actually uses, not the raw ~5MB response) since the
 * real item catalog only changes with real Skyblock content updates, not every session - refreshed in
 * the background if the cache is missing or older than {@link #CACHE_TTL_MS}, while the previous cache
 * (however old) keeps serving {@link #getItems()} in the meantime so the panel is never empty just
 * because a background refresh hasn't finished yet.
 */
public final class SkyblockItemRepository {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-itembrowser");
    private static final String ITEMS_URL = "https://api.hypixel.net/v2/resources/skyblock/items";
    private static final Path CACHE_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-itembrowser-items-cache.json");
    private static final long CACHE_TTL_MS = 12L * 60 * 60 * 1000;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private static volatile List<SkyblockItemEntry> items = List.of();
    private static volatile long lastFetchAtMs = 0;
    private static final AtomicBoolean refreshing = new AtomicBoolean(false);
    private static volatile boolean triedCache = false;

    private SkyblockItemRepository() {
    }

    public static List<SkyblockItemEntry> getItems() {
        ensureLoaded();
        return items;
    }

    public static boolean isRefreshing() {
        return refreshing.get();
    }

    /** Loads the on-disk cache (any age) on first real use so the panel has data immediately, then kicks
     *  off a background refresh if that cache is missing or stale. Safe to call every render frame -
     *  every real check here is a cheap volatile/AtomicBoolean read after the first call. */
    private static void ensureLoaded() {
        if (!triedCache) {
            triedCache = true;
            loadFromDisk();
        }
        if (System.currentTimeMillis() - lastFetchAtMs > CACHE_TTL_MS) {
            refreshAsync();
        }
    }

    public static void refreshAsync() {
        if (!refreshing.compareAndSet(false, true)) {
            return;
        }
        CompletableFuture.supplyAsync(SkyblockItemRepository::fetchAndParse).whenComplete((fetched, error) -> {
            refreshing.set(false);
            if (error != null || fetched == null || fetched.isEmpty()) {
                LOGGER.warn("[ItemBrowser] Real item catalog refresh failed - keeping previous data.", error);
                return;
            }
            items = fetched;
            lastFetchAtMs = System.currentTimeMillis();
            saveToDisk(fetched);
        });
    }

    private static List<SkyblockItemEntry> fetchAndParse() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(ITEMS_URL))
                    .header("User-Agent", "Killer560sMod-ItemBrowser/1.0")
                    .GET()
                    .build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                return List.of();
            }
            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            if (!root.has("success") || !root.get("success").getAsBoolean() || !root.has("items")) {
                return List.of();
            }
            return parseItems(root.getAsJsonArray("items"));
        } catch (Exception e) {
            return List.of();
        }
    }

    private static List<SkyblockItemEntry> parseItems(JsonArray array) {
        List<SkyblockItemEntry> result = new ArrayList<>(array.size());
        for (var element : array) {
            JsonObject item = element.getAsJsonObject();
            if (!item.has("id") || !item.has("name") || !item.has("material")) {
                continue;
            }
            String id = item.get("id").getAsString();
            String name = item.get("name").getAsString();
            String material = item.get("material").getAsString();
            String itemModel = item.has("item_model") ? item.get("item_model").getAsString() : null;
            String skinValue = null;
            String skinSignature = null;
            if (item.has("skin") && item.get("skin").isJsonObject()) {
                JsonObject skin = item.getAsJsonObject("skin");
                skinValue = skin.has("value") ? skin.get("value").getAsString() : null;
                skinSignature = skin.has("signature") ? skin.get("signature").getAsString() : null;
            }
            result.add(new SkyblockItemEntry(id, name, material, itemModel, skinValue, skinSignature));
        }
        return result;
    }

    private static void loadFromDisk() {
        if (!Files.exists(CACHE_PATH)) {
            return;
        }
        try {
            String json = Files.readString(CACHE_PATH, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            long cachedAt = root.has("cachedAtMs") ? root.get("cachedAtMs").getAsLong() : 0;
            List<SkyblockItemEntry> cached = parseItems(root.getAsJsonArray("items"));
            if (!cached.isEmpty()) {
                items = cached;
                lastFetchAtMs = cachedAt;
            }
        } catch (Exception e) {
            LOGGER.warn("[ItemBrowser] Failed to read real item catalog cache.", e);
        }
    }

    private static void saveToDisk(List<SkyblockItemEntry> toSave) {
        try {
            Files.createDirectories(CACHE_PATH.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("cachedAtMs", lastFetchAtMs);
            JsonArray array = new JsonArray();
            for (SkyblockItemEntry entry : toSave) {
                JsonObject obj = new JsonObject();
                obj.addProperty("id", entry.id());
                obj.addProperty("name", entry.name());
                obj.addProperty("material", entry.material());
                if (entry.itemModel() != null) {
                    obj.addProperty("item_model", entry.itemModel());
                }
                if (entry.skinValue() != null) {
                    JsonObject skin = new JsonObject();
                    skin.addProperty("value", entry.skinValue());
                    if (entry.skinSignature() != null) {
                        skin.addProperty("signature", entry.skinSignature());
                    }
                    obj.add("skin", skin);
                }
                array.add(obj);
            }
            root.add("items", array);
            Files.writeString(CACHE_PATH, root.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.warn("[ItemBrowser] Failed to write real item catalog cache.", e);
        }
    }
}
