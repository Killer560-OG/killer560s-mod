package com.killer560.hub.auction;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.itembrowser.SkyblockItemEntry;
import com.killer560.hub.itembrowser.SkyblockItemRepository;
import com.killer560.hub.itembrowser.SkyblockItemStackFactory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Real Hypixel Bazaar scanner - killer560's item 8.1, Bazaar half ("the same for Bazaar"). One GET to the
 * keyless {@code https://api.hypixel.net/v2/skyblock/bazaar} resource returns every product at once (no
 * pagination, unlike the Auction House), so a "scan" here is just that one request, off the render/tick
 * thread, on the same background/auto-refresh schedule as the item catalog and the AH scan. Icons and
 * display names come from the shared real item catalog ({@link SkyblockItemRepository} /
 * {@link SkyblockItemStackFactory}), per the brief - the Bazaar resource itself has no icon data.
 */
public final class BazaarApi {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-bazaar");
    private static final String BAZAAR_URL = "https://api.hypixel.net/v2/skyblock/bazaar";
    private static final long AUTO_RESCAN_MINUTES = 5;

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "killer560smod-bazaar-schedule");
        t.setDaemon(true);
        return t;
    });

    private static volatile List<BazaarProduct> products = List.of();
    private static volatile long lastFetchedMs = 0;
    private static volatile String lastError = null;
    private static final AtomicBoolean refreshing = new AtomicBoolean(false);
    private static volatile boolean autoStarted = false;

    private BazaarApi() {
    }

    public static List<BazaarProduct> getProducts() {
        ensureAutoStarted();
        return products;
    }

    public static boolean isRefreshing() {
        return refreshing.get();
    }

    public static long getLastFetchedMs() {
        return lastFetchedMs;
    }

    public static String getLastError() {
        return lastError;
    }

    public static void ensureAutoStarted() {
        if (autoStarted) {
            return;
        }
        autoStarted = true;
        runCycle();
    }

    private static void runCycle() {
        refreshAsync().whenComplete((v, e) -> SCHEDULER.schedule(BazaarApi::runCycle, AUTO_RESCAN_MINUTES, TimeUnit.MINUTES));
    }

    public static CompletableFuture<Void> refreshAsync() {
        if (!refreshing.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.supplyAsync(BazaarApi::fetchAndParse).<Void>handle((fetched, error) -> {
            refreshing.set(false);
            if (error != null) {
                lastError = error.getMessage() != null ? error.getMessage() : error.toString();
                LOGGER.warn("[Bazaar] Refresh failed", error);
                lastFetchedMs = System.currentTimeMillis();
                return null;
            }
            if (fetched != null && !fetched.isEmpty()) {
                products = fetched;
                lastError = null;
            } else {
                lastError = "Bazaar fetch returned no products.";
            }
            lastFetchedMs = System.currentTimeMillis();
            return null;
        });
    }

    private static List<BazaarProduct> fetchAndParse() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(BAZAAR_URL))
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", "Killer560sMod-Bazaar/1.0")
                    .GET()
                    .build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                return List.of();
            }
            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            if (!root.has("success") || !root.get("success").getAsBoolean() || !root.has("products")) {
                return List.of();
            }
            JsonObject productsJson = root.getAsJsonObject("products");
            List<BazaarProduct> out = new ArrayList<>(productsJson.size());
            for (Map.Entry<String, JsonElement> entry : productsJson.entrySet()) {
                BazaarProduct product = parseProduct(entry.getKey(), entry.getValue().getAsJsonObject());
                if (product != null) {
                    out.add(product);
                }
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static BazaarProduct parseProduct(String id, JsonObject obj) {
        if (!obj.has("quick_status")) {
            return null;
        }
        JsonObject qs = obj.getAsJsonObject("quick_status");
        double buy = qs.has("buyPrice") ? qs.get("buyPrice").getAsDouble() : 0;
        double sell = qs.has("sellPrice") ? qs.get("sellPrice").getAsDouble() : 0;
        long buyVol = qs.has("buyVolume") ? qs.get("buyVolume").getAsLong() : 0;
        long sellVol = qs.has("sellVolume") ? qs.get("sellVolume").getAsLong() : 0;

        SkyblockItemEntry catalogEntry = SkyblockItemRepository.findById(id);
        String displayName = catalogEntry != null ? catalogEntry.name() : titleCase(id);
        ItemStack icon = catalogEntry != null ? SkyblockItemStackFactory.build(catalogEntry) : new ItemStack(Items.PAPER);
        return new BazaarProduct(id, displayName, buy, sell, buyVol, sellVol, icon);
    }

    private static String titleCase(String raw) {
        String[] words = raw.toLowerCase(Locale.ROOT).replace(':', '_').split("_");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (w.isEmpty()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }
}
