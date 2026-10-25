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
    /** When Hypixel built the snapshot in {@link #products}, on this machine's clock (0: none yet). */
    private static volatile long productsUpdatedMs = 0;
    private static volatile String lastError = null;
    private static final AtomicBoolean refreshing = new AtomicBoolean(false);
    private static volatile boolean autoStarted = false;

    private BazaarApi() {
    }

    public static List<BazaarProduct> getProducts() {
        ensureAutoStarted();
        return products;
    }

    /** The last fetched products WITHOUT starting the five-minute background loop: for the Bazaar Flipper's autopilot,
     *  which refreshes on its own schedule ({@link #refreshAsync()}) only while it runs. */
    public static List<BazaarProduct> peekProducts() {
        return products;
    }

    public static boolean isRefreshing() {
        return refreshing.get();
    }

    /** When the last refresh ENDED, successful or not (a failed one sets it too). */
    public static long getLastFetchedMs() {
        return lastFetchedMs;
    }

    /**
     * When Hypixel built the snapshot {@link #peekProducts()} returns, on this machine's clock: the response's own
     * {@code lastUpdated}, shifted by how far this clock is from the server's {@code Date} header when there is one (so a
     * client clock set wrong does not age it). A failed refresh leaves it alone, which is the point - the products keep
     * their old age. 0 before any snapshot.
     */
    public static long getProductsUpdatedMs() {
        return productsUpdatedMs;
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
                Throwable cause = error instanceof java.util.concurrent.CompletionException && error.getCause() != null
                        ? error.getCause() : error;
                lastError = cause.getMessage() != null ? cause.getMessage() : cause.toString();
                LOGGER.warn("[Bazaar] Refresh failed", error);
                lastFetchedMs = System.currentTimeMillis();
                return null;
            }
            if (fetched != null && !fetched.products().isEmpty()) {
                products = fetched.products();
                productsUpdatedMs = fetched.updatedMs();
                lastError = null;
            } else {
                lastError = "Bazaar fetch returned no products.";
            }
            lastFetchedMs = System.currentTimeMillis();
            return null;
        });
    }

    /**
     * Every product's instant-buy book together with the response's own {@code lastUpdated} (epoch ms, when Hypixel
     * built that snapshot; 0 when absent or on failure). The request asks every cache on the way not to answer from a
     * stored copy ({@code Cache-Control: no-cache} and a never-repeated query parameter): the API sits behind Cloudflare
     * with {@code s-maxage=300} (response headers, 2026-10-17), so a cached snapshot could be minutes old. A cache that
     * ignores both still cannot fool the caller, which judges {@code lastUpdated} itself (Bazaar Flip refuses one older
     * than 60 s).
     */
    public record InstantBuyBooks(Map<String, List<BazaarOrderLevel>> books, long lastUpdatedMs) {
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
     * <p>Blocking; call off the render thread.
     *
     * @return product id -&gt; levels sorted ascending by {@code pricePerUnit} (cheapest first, i.e. the
     * order a real instant-buy consumes them in). Never null; an empty map on any failure, never partial.
     */
    public static InstantBuyBooks fetchInstantBuyBooks() {
        try {
            String url = BAZAAR_URL + (BAZAAR_URL.contains("?") ? "&" : "?") + "_=" + System.currentTimeMillis();
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", "Killer560sMod-Bazaar/1.0")
                    .header("Cache-Control", "no-cache")
                    .header("Pragma", "no-cache")
                    .GET()
                    .build();
            HttpResponse<String> response = ModNet.send(HTTP, request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                LOGGER.warn("[Bazaar] Order-book fetch returned HTTP {}", response.statusCode());
                return new InstantBuyBooks(Map.of(), 0L);
            }
            return parseInstantBuyBooks(response.body());
        } catch (Exception e) {
            LOGGER.warn("[Bazaar] Order-book fetch failed", e);
            return new InstantBuyBooks(Map.of(), 0L);
        }
    }

    /** The API's JSON to {@link InstantBuyBooks} (pure; the testkit feeds it snapshots). */
    public static InstantBuyBooks parseInstantBuyBooks(String body) {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            if (!root.has("success") || !root.get("success").getAsBoolean() || !root.has("products")) {
                return new InstantBuyBooks(Map.of(), 0L);
            }
            long lastUpdated = root.has("lastUpdated") ? root.get("lastUpdated").getAsLong() : 0L;
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
                    // Never-throwing reads: a JSON null in one level used to throw out of the whole parse and
                    // empty every product's book. Now that level reads 0 and is skipped below; the rest stay.
                    double ppu = com.killer560.hub.util.ConfigJson.getDouble(lvl, "pricePerUnit", 0);
                    long amount = com.killer560.hub.util.ConfigJson.getLong(lvl, "amount", 0);
                    int orders = com.killer560.hub.util.ConfigJson.getInt(lvl, "orders", 0);
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
            return new InstantBuyBooks(Map.copyOf(out), lastUpdated);
        } catch (Exception e) {
            LOGGER.warn("[Bazaar] Order-book parse failed", e);
            return new InstantBuyBooks(Map.of(), 0L);
        }
    }

    /** One fetched snapshot and when Hypixel built it (this machine's clock). */
    private record Fetched(List<BazaarProduct> products, long updatedMs) {
    }

    private static Fetched fetchAndParse() {
        try {
            // Past every cache, as fetchInstantBuyBooks does: the Bazaar Flipper refuses a snapshot older than a few
            // minutes, and Cloudflare's s-maxage=300 could otherwise hand back one five minutes old.
            String url = BAZAAR_URL + (BAZAAR_URL.contains("?") ? "&" : "?") + "_=" + System.currentTimeMillis();
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", "Killer560sMod-Bazaar/1.0")
                    .header("Cache-Control", "no-cache")
                    .header("Pragma", "no-cache")
                    .GET()
                    .build();
            HttpResponse<String> response = ModNet.send(HTTP, request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                // Thrown, not swallowed: refreshAsync's error branch logs it and puts the status in getLastError.
                throw new IllegalStateException("Bazaar API returned HTTP " + response.statusCode());
            }
            long now = System.currentTimeMillis();
            long serverNow = response.headers().firstValue("date").map(BazaarApi::httpDate).orElse(0L);
            return new Fetched(parseResponse(response.body()), localUpdatedMs(lastUpdatedOf(response.body()), serverNow, now));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            // A network failure or timeout: handed to refreshAsync, which logs the real reason.
            throw new java.util.concurrent.CompletionException(e);
        }
    }

    /** An HTTP {@code Date} header in epoch ms, 0 when it does not parse. */
    private static long httpDate(String value) {
        try {
            return java.time.ZonedDateTime.parse(value, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant().toEpochMilli();
        } catch (Exception e) {
            return 0L;
        }
    }

    /** The response's {@code lastUpdated} (Hypixel's clock, epoch ms), 0 when absent. */
    static long lastUpdatedOf(String body) {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            return root.has("lastUpdated") ? root.get("lastUpdated").getAsLong() : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * {@code lastUpdated} on this machine's clock: its age by the server's own clock ({@code serverNowMs}, the Date
     * header) taken back from {@code localNowMs}; without a Date header, {@code lastUpdated} as it is. Never in the
     * future. Without {@code lastUpdated}, the fetch time (the snapshot is at least that fresh as far as anyone can say).
     */
    static long localUpdatedMs(long lastUpdatedMs, long serverNowMs, long localNowMs) {
        if (lastUpdatedMs <= 0) {
            return localNowMs;
        }
        long t = serverNowMs > 0 ? localNowMs - Math.max(0, serverNowMs - lastUpdatedMs) : lastUpdatedMs;
        return Math.min(t, localNowMs);
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

    /** Replaces the product list with {@code body}'s, as if it had just been fetched (its {@code lastUpdated} is the
     *  snapshot's age, as for a live fetch with no Date header). Returns the product count. */
    public static int applyResponseForTest(String body) {
        List<BazaarProduct> parsed = parseResponse(body);
        if (!parsed.isEmpty()) {
            long now = System.currentTimeMillis();
            products = parsed;
            productsUpdatedMs = localUpdatedMs(lastUpdatedOf(body), 0L, now);
            lastFetchedMs = now;
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
