package com.killer560.hub.auction;

import com.killer560.hub.util.ModNet;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.itembrowser.SkyblockItemEntry;
import com.killer560.hub.itembrowser.SkyblockItemRepository;
import org.slf4j.Logger;
import com.killer560.hub.util.ModLog;

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
 * thread, on the same background/auto-refresh schedule as the item catalog and the AH scan. Names, categories and
 * rarity come from the bundled {@link BazaarCatalog} (then the live {@link SkyblockItemRepository} for products newer
 * than it); icons from {@link BazaarIcons}, built on the render thread - the Bazaar resource itself has no icon data.
 */
public final class BazaarApi {

    private static final Logger LOGGER = ModLog.get("killer560smod-bazaar");
    private static final String BAZAAR_URL = ModNet.url("hypixel", "https://api.hypixel.net/v2/skyblock/bazaar");
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

    /**
     * One on-demand GET that returns every product's real <b>instant-buy</b> order book, cheapest level
     * first. Used by the Bazaar-to-NPC Flipper, which has to walk the book level by level to price a real
     * purchase. Deliberately <b>not</b> wired into {@link #ensureAutoStarted()}'s five-minute loop and it
     * never touches {@link #products}: the flipper only scans while it is actually running (killer560,
     * 2026-09-29: "only have the rescan go while the bot is running"), and the always-on Auction House scan
     * is already a known performance complaint.
     *
     * <p><b>The naming trap.</b> Hypixel's two summaries are named the opposite of how they read:
     * <ul>
     *   <li>{@code buy_summary} is the book you <b>instant-buy out of</b>, i.e. what you PAY. Verified live
     *       on {@code VIBRANT_CORAL} (2026-09-29):
     *       {@code quick_status.buyPrice} 3324220.9 lines up with {@code buy_summary[0].pricePerUnit}
     *       3324220.8, and {@code quick_status.sellPrice} 221606.1 lines up with
     *       {@code sell_summary[0].pricePerUnit} 221606.1 exactly. So {@code buy_summary} is the expensive
     *       side and is the one an instant BUY consumes.</li>
     *   <li>{@code sell_summary} is the cheap side, the book an instant SELL fills into.</li>
     * </ul>
     * Reading them the other way round produced a fake 1.6-billion-coin "flip" the first time this was
     * written. This method returns {@code buy_summary} only, because that is the only book a
     * buy-from-Bazaar/sell-to-NPC flip ever touches.
     *
     * <p>{@code quick_status.buyPrice} is <b>not</b> the top of that book - it is a weighted average of the
     * top orders, and it matched the exact top price on only 628 of 1833 products with a book (measured
     * 2026-09-29). Anything sizing a real purchase must walk the levels, not read that average.
     *
     * @return product id -&gt; levels sorted ascending by {@code pricePerUnit} (cheapest first, i.e. the
     * order a real instant-buy consumes them in). Empty map on any failure - never null, never partial.
     */
    public static CompletableFuture<Map<String, List<BazaarOrderLevel>>> fetchInstantBuyBooksAsync() {
        return CompletableFuture.supplyAsync(BazaarApi::fetchInstantBuyBooks);
    }

    private static Map<String, List<BazaarOrderLevel>> fetchInstantBuyBooks() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(BAZAAR_URL))
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", "Killer560sMod-Bazaar/1.0")
                    .GET()
                    .build();
            HttpResponse<String> response = ModNet.send(HTTP, request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                LOGGER.warn("[Bazaar] Order-book fetch returned HTTP {}", response.statusCode());
                return Map.of();
            }
            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            if (!root.has("success") || !root.get("success").getAsBoolean() || !root.has("products")) {
                return Map.of();
            }
            JsonObject productsJson = root.getAsJsonObject("products");
            Map<String, List<BazaarOrderLevel>> out = new java.util.HashMap<>(productsJson.size() * 2);
            for (Map.Entry<String, JsonElement> entry : productsJson.entrySet()) {
                if (!entry.getValue().isJsonObject()) {
                    continue;
                }
                JsonObject obj = entry.getValue().getAsJsonObject();
                // buy_summary ONLY - see this method's doc for why that is the instant-BUY side.
                if (!obj.has("buy_summary") || !obj.get("buy_summary").isJsonArray()) {
                    continue;
                }
                List<BazaarOrderLevel> levels = new ArrayList<>();
                for (JsonElement el : obj.getAsJsonArray("buy_summary")) {
                    if (!el.isJsonObject()) {
                        continue;
                    }
                    JsonObject lvl = el.getAsJsonObject();
                    double ppu = lvl.has("pricePerUnit") ? lvl.get("pricePerUnit").getAsDouble() : 0;
                    long amount = lvl.has("amount") ? lvl.get("amount").getAsLong() : 0;
                    int orders = lvl.has("orders") ? lvl.get("orders").getAsInt() : 0;
                    if (ppu > 0 && amount > 0) {
                        levels.add(new BazaarOrderLevel(ppu, amount, orders));
                    }
                }
                if (levels.isEmpty()) {
                    continue;
                }
                levels.sort(java.util.Comparator.comparingDouble(BazaarOrderLevel::pricePerUnit));
                out.put(entry.getKey(), List.copyOf(levels));
            }
            return Map.copyOf(out);
        } catch (Exception e) {
            LOGGER.warn("[Bazaar] Order-book fetch failed", e);
            return Map.of();
        }
    }

    private static List<BazaarProduct> fetchAndParse() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(BAZAAR_URL))
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", "Killer560sMod-Bazaar/1.0")
                    .GET()
                    .build();
            HttpResponse<String> response = ModNet.send(HTTP, request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                return List.of();
            }
            return parseResponse(response.body());
        } catch (Exception e) {
            return List.of();
        }
    }

    /** Levels of each side of the order book kept per product: what the browser's detail view shows. */
    public static final int BOOK_DEPTH = 6;

    /**
     * Parses one {@code /v2/skyblock/bazaar} response body into products. Public so a test can feed a recorded or
     * synthetic response through exactly the code a live fetch uses ({@link #applyResponseForTest}).
     */
    public static List<BazaarProduct> parseResponse(String body) {
        BazaarCatalog.ensureLoaded();
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        if (!root.has("success") || !root.get("success").getAsBoolean() || !root.has("products")) {
            return List.of();
        }
        JsonObject productsJson = root.getAsJsonObject("products");
        List<BazaarProduct> out = new ArrayList<>(productsJson.size());
        for (Map.Entry<String, JsonElement> entry : productsJson.entrySet()) {
            try {
                if (!entry.getValue().isJsonObject()) {
                    continue;
                }
                BazaarProduct product = parseProduct(entry.getKey(), entry.getValue().getAsJsonObject());
                if (product != null) {
                    out.add(product);
                }
            } catch (Exception e) {
                // One malformed product must not cost the other two thousand.
            }
        }
        return out;
    }

    /** Replaces the product list with {@code body}'s, as if it had just been fetched. Returns the product count. */
    public static int applyResponseForTest(String body) {
        List<BazaarProduct> parsed = parseResponse(body);
        if (!parsed.isEmpty()) {
            products = parsed;
            lastFetchedMs = System.currentTimeMillis();
            lastError = null;
        }
        return parsed.size();
    }

    private static BazaarProduct parseProduct(String id, JsonObject obj) {
        if (!obj.has("quick_status")) {
            return null;
        }
        JsonObject qs = obj.getAsJsonObject("quick_status");
        double buy = num(qs, "buyPrice");
        double sell = num(qs, "sellPrice");
        long buyVol = (long) num(qs, "buyVolume");
        long sellVol = (long) num(qs, "sellVolume");
        long buyWeek = (long) num(qs, "buyMovingWeek");
        long sellWeek = (long) num(qs, "sellMovingWeek");
        int buyOrders = (int) num(qs, "buyOrders");
        int sellOrders = (int) num(qs, "sellOrders");
        // sell_summary = the buy orders (an instant SELL fills them), best = highest; buy_summary = the sell offers,
        // best = lowest. See fetchInstantBuyBooks for the naming trap.
        List<BazaarOrderLevel> buyOrderBook = book(obj, "sell_summary", false);
        List<BazaarOrderLevel> sellOfferBook = book(obj, "buy_summary", true);

        BazaarCatalog.Entry row = BazaarCatalog.get(id);
        SkyblockItemEntry catalogEntry = row == null ? SkyblockItemRepository.findById(id) : null;
        String displayName = row != null && row.name() != null ? row.name()
                : catalogEntry != null ? catalogEntry.name() : titleCase(id);
        String tier = row != null ? row.tier() : catalogEntry != null ? catalogEntry.tier() : null;
        int groupIndex = row != null ? row.group() : -1;
        BazaarCatalog.Group group = BazaarCatalog.group(groupIndex);
        String category = group != null ? group.category() : "Oddities";
        String groupName = group != null ? group.name() : "Other";
        return new BazaarProduct(id, displayName, buy, sell, buyVol, sellVol, buyWeek, sellWeek, buyOrders, sellOrders,
                category, groupName, groupIndex, tier, row != null && row.ultimate(), buyOrderBook, sellOfferBook);
    }

    private static double num(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsDouble() : 0;
    }

    private static List<BazaarOrderLevel> book(JsonObject obj, String key, boolean ascending) {
        if (!obj.has(key) || !obj.get(key).isJsonArray()) {
            return List.of();
        }
        List<BazaarOrderLevel> levels = new ArrayList<>();
        for (JsonElement el : obj.getAsJsonArray(key)) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject lvl = el.getAsJsonObject();
            double ppu = num(lvl, "pricePerUnit");
            long amount = (long) num(lvl, "amount");
            int orders = (int) num(lvl, "orders");
            if (ppu > 0 && amount > 0) {
                levels.add(new BazaarOrderLevel(ppu, amount, orders));
            }
        }
        java.util.Comparator<BazaarOrderLevel> byPrice = java.util.Comparator.comparingDouble(BazaarOrderLevel::pricePerUnit);
        levels.sort(ascending ? byPrice : byPrice.reversed());
        return List.copyOf(levels.subList(0, Math.min(BOOK_DEPTH, levels.size())));
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
