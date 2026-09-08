package com.killer560.hub.rngmeter;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

/**
 * Live Bazaar instant-sell and Auction House lowest-BIN prices for RNG-meter-eligible items.
 * Mirrors the standalone "RNG Meter" spreadsheet tool's approach (OneDrive\Desktop\RNG Meter\code\index.js):
 * bazaar via one GET, AH lowest-BIN by paging the raw auctions endpoint and decoding each BIN
 * listing's gzip-compressed NBT item_bytes to read its Hypixel internal item ID. Uses Minecraft's
 * own NBT reader ({@link NbtIo}) since item_bytes is genuine Minecraft ItemStack NBT - no need for
 * a separate NBT parsing library.
 */
public final class HypixelMarketPrices {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-rngmeter-prices");
    private static final String BAZAAR_URL = "https://api.hypixel.net/v2/skyblock/bazaar";
    private static final String AUCTIONS_URL = "https://api.hypixel.net/skyblock/auctions";
    private static final int PAGE_BATCH_SIZE = 10;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final Map<String, Long> bazaarSellPrice = new ConcurrentHashMap<>();
    private final Map<String, Long> ahLowestBin = new ConcurrentHashMap<>();

    private volatile boolean refreshing = false;
    private volatile long lastRefreshedAtMs = 0;
    private volatile String lastError = null;

    public boolean isRefreshing() {
        return refreshing;
    }

    public long getLastRefreshedAtMs() {
        return lastRefreshedAtMs;
    }

    public String getLastError() {
        return lastError;
    }

    /** How many items currently have a resolved Bazaar price - useful to tell a slow AH scan from a stuck one. */
    public int getBazaarPriceCount() {
        return bazaarSellPrice.size();
    }

    /** How many items currently have a resolved AH lowest-BIN price - grows as the auction scan pages through. */
    public int getAhPriceCount() {
        return ahLowestBin.size();
    }

    /** True if this exact ID exists as a real Bazaar product (regardless of whether a price was resolvable for it). */
    public boolean hasBazaarProduct(String id) {
        return bazaarSellPrice.containsKey(id);
    }

    /**
     * Live price for one item, in coins, or null if not found anywhere. AH-sourced items are
     * looked up on the AH directly; Bazaar-sourced items check the Bazaar first and fall back to
     * the AH lowest-BIN if the item isn't listed there (some items drift between the two, or
     * `RngItemData`'s source tag is simply wrong for it).
     */
    public Long getPrice(RngItem item) {
        if (item.source() == RngSource.AH) {
            return ahLowestBin.get(item.id());
        }
        Long bazaarPrice = bazaarSellPrice.get(item.id());
        return bazaarPrice != null ? bazaarPrice : ahLowestBin.get(item.id());
    }

    private static final long STALE_AFTER_MS = 10 * 60 * 1000L;

    private final ScheduledExecutorService autoRefreshScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "killer560smod-rngmeter-autorefresh");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean autoRefreshStarted = false;

    /**
     * Starts the fully-automatic refresh loop: refreshes immediately, then reschedules the next
     * refresh {@link RngMeterConfig#getRefreshIntervalMinutes()} minutes after each refresh
     * *completes* (not a fixed-rate clock) - so a slow AH scan pushes the next one back rather
     * than overlapping it. Safe to call more than once; only the first call has any effect.
     */
    public void startAutoRefresh() {
        if (autoRefreshStarted) {
            return;
        }
        autoRefreshStarted = true;
        runAutoRefreshCycle();
    }

    private void runAutoRefreshCycle() {
        refreshAsync();
        autoRefreshScheduler.schedule(this::runAutoRefreshCycle,
                RngMeterConfig.getInstance().getRefreshIntervalMinutes(), TimeUnit.MINUTES);
    }

    /** Refreshes only if never fetched yet or the last fetch is older than 10 minutes. */
    public void refreshIfStale() {
        if (!refreshing && (lastRefreshedAtMs == 0 || System.currentTimeMillis() - lastRefreshedAtMs > STALE_AFTER_MS)) {
            refreshAsync();
        }
    }

    /** Kicks off a background refresh of both price maps. Safe to call repeatedly; no-ops while already running. */
    public void refreshAsync() {
        if (refreshing) {
            return;
        }
        refreshing = true;
        lastError = null;
        long startedAt = System.currentTimeMillis();
        LOGGER.info("Price refresh starting (wanted AH ids: {})", wantedIdCountForLogging());
        CompletableFuture.runAsync(this::fetchBazaarPrices)
                .thenCompose(v -> fetchAhLowestBins(HypixelApiKeyProvider.getKey()))
                .whenComplete((v, err) -> {
                    if (err != null) {
                        lastError = err.getMessage() != null ? err.getMessage() : err.toString();
                    }
                    lastRefreshedAtMs = System.currentTimeMillis();
                    refreshing = false;
                    LOGGER.info("Price refresh finished in {}ms: {} bazaar prices, {} AH prices, error={}",
                            lastRefreshedAtMs - startedAt, bazaarSellPrice.size(), ahLowestBin.size(), lastError);
                });
    }

    private static int wantedIdCountForLogging() {
        Set<String> ids = new HashSet<>();
        for (List<RngItem> category : RngItemData.ALL_CATEGORIES) {
            for (RngItem item : category) {
                ids.add(item.id());
            }
        }
        ids.addAll(RngItemNames.BY_NAME.values());
        return ids.size();
    }

    private void fetchBazaarPrices() {
        try {
            JsonObject resp = getJson(BAZAAR_URL);
            if (resp == null || !resp.has("success") || !resp.get("success").getAsBoolean()) {
                return;
            }
            boolean useBuyPrice = RngMeterConfig.getInstance().isUseInstantBuyPrice();
            String priceField = useBuyPrice ? "buyPrice" : "sellPrice";
            JsonObject products = resp.getAsJsonObject("products");
            for (Map.Entry<String, JsonElement> entry : products.entrySet()) {
                JsonObject quickStatus = entry.getValue().getAsJsonObject().getAsJsonObject("quick_status");
                if (quickStatus != null && quickStatus.has(priceField)) {
                    bazaarSellPrice.put(entry.getKey(), quickStatus.get(priceField).getAsLong());
                }
            }
        } catch (Exception e) {
            lastError = "Bazaar fetch failed: " + e.getMessage();
        }
    }

    private CompletableFuture<Void> fetchAhLowestBins(String apiKey) {
        // Scans for every item's ID, not just ones tagged source=AH, so Bazaar-sourced items have
        // AH data available as a fallback in getPrice() if the Bazaar doesn't have them listed.
        // Also includes every ID in RngItemNames, since live menu scanning can price items outside
        // RngItemData's own hand-typed table.
        Set<String> wantedIds = new HashSet<>();
        for (List<RngItem> category : RngItemData.ALL_CATEGORIES) {
            for (RngItem item : category) {
                wantedIds.add(item.id());
            }
        }
        wantedIds.addAll(RngItemNames.BY_NAME.values());

        return CompletableFuture.supplyAsync(() -> getJson(auctionsUrl(apiKey, 0)))
                .thenCompose(first -> {
                    if (first == null || !first.has("success") || !first.get("success").getAsBoolean()) {
                        lastError = "AH fetch failed: unexpected response on page 0";
                        return CompletableFuture.completedFuture(null);
                    }
                    processAuctionPage(first, wantedIds);
                    int totalPages = first.has("totalPages") ? first.get("totalPages").getAsInt() : 1;

                    CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
                    for (int start = 1; start < totalPages; start += PAGE_BATCH_SIZE) {
                        int batchStart = start;
                        int batchEnd = Math.min(start + PAGE_BATCH_SIZE, totalPages);
                        chain = chain.thenCompose(v -> {
                            CompletableFuture<?>[] pageFutures = new CompletableFuture[batchEnd - batchStart];
                            for (int p = batchStart; p < batchEnd; p++) {
                                int page = p;
                                pageFutures[p - batchStart] = CompletableFuture
                                        .supplyAsync(() -> getJson(auctionsUrl(apiKey, page)))
                                        .thenAccept(pageJson -> {
                                            if (pageJson != null) {
                                                processAuctionPage(pageJson, wantedIds);
                                            }
                                        });
                            }
                            return CompletableFuture.allOf(pageFutures);
                        });
                    }
                    return chain;
                });
    }

    private void processAuctionPage(JsonObject page, Set<String> wantedIds) {
        JsonArray auctions = page.getAsJsonArray("auctions");
        if (auctions == null) {
            return;
        }
        for (JsonElement el : auctions) {
            JsonObject auction = el.getAsJsonObject();
            if (!auction.has("bin") || !auction.get("bin").getAsBoolean()) {
                continue;
            }
            String itemBytes = auction.has("item_bytes") ? auction.get("item_bytes").getAsString() : null;
            if (itemBytes == null) {
                continue;
            }
            String id = extractItemId(itemBytes);
            if (id == null) {
                continue;
            }
            // Rune/pet ids are synthesized live from whatever name a menu scan happens to show
            // (see extractItemId and RngMeterOverlay.resolveId), so the exact string can never be
            // known ahead of time to seed wantedIds with - captured unconditionally instead. Small
            // and bounded either way (a few dozen rune types x a handful of levels, similarly for
            // pets), so this doesn't meaningfully widen the scan.
            boolean alwaysWanted = id.startsWith("RUNE_") || id.startsWith("PET_");
            if (!alwaysWanted && !wantedIds.contains(id)) {
                continue;
            }
            long price = auction.get("starting_bid").getAsLong();
            ahLowestBin.merge(id, price, Math::min);
        }
    }

    /**
     * Decodes a base64 gzip-compressed NBT item_bytes blob into the Hypixel internal item ID.
     * {@link NbtIo#readCompressed} does its own gzip decompression internally (that's what
     * "Compressed" means here) - it must be handed the still-compressed bytes directly, not a
     * stream that's already been run through {@link GZIPInputStream} first. Feeding it
     * already-decompressed data throws immediately (invalid gzip header), which - caught by the
     * catch-all below - silently turned every single item lookup into a permanent no-op.
     *
     * <p>Pets are a special case: every pet auction shares the generic id {@code "PET"} regardless
     * of type - the actual pet type lives in a separate {@code petInfo} JSON string. Synthesizes a
     * {@code "PET_<TYPE>"} id for those instead (confirmed against real AH data - e.g. a level 1
     * Spirit pet auction's ExtraAttributes.id is literally "PET", petInfo.type is "SPIRIT").
     *
     * <p>Runes are the same kind of special case: every rune auction shares the generic id
     * {@code "RUNE"}, with the actual type and level in a separate {@code runes} NBT compound
     * (confirmed against real AH data - e.g. {@code {ENDERSNAKE: 1}}). Synthesizes a
     * {@code "RUNE_<TYPE>_<LEVEL>"} id instead.
     */
    private static String extractItemId(String base64ItemBytes) {
        try {
            byte[] compressed = Base64.getDecoder().decode(base64ItemBytes);
            CompoundTag root = NbtIo.readCompressed(new ByteArrayInputStream(compressed), NbtAccounter.unlimitedHeap());
            ListTag items = root.getListOrEmpty("i");
            if (items.size() == 0) {
                return null;
            }
            CompoundTag item = items.getCompoundOrEmpty(0);
            CompoundTag tag = item.getCompoundOrEmpty("tag");
            CompoundTag extraAttributes = tag.getCompoundOrEmpty("ExtraAttributes");
            String id = extraAttributes.getStringOr("id", null);
            if ("PET".equals(id)) {
                String petInfo = extraAttributes.getStringOr("petInfo", null);
                if (petInfo == null) {
                    return null;
                }
                String type = JsonParser.parseString(petInfo).getAsJsonObject().get("type").getAsString();
                return "PET_" + type;
            }
            if ("RUNE".equals(id)) {
                CompoundTag runes = extraAttributes.getCompoundOrEmpty("runes");
                if (runes.isEmpty()) {
                    return null;
                }
                String runeType = runes.keySet().iterator().next();
                int level = runes.getIntOr(runeType, 1);
                return "RUNE_" + runeType + "_" + level;
            }
            return id;
        } catch (Exception e) {
            return null;
        }
    }

    private static String auctionsUrl(String apiKey, int page) {
        String base = AUCTIONS_URL + "?page=" + page;
        return (apiKey != null && !apiKey.isBlank()) ? base + "&key=" + apiKey : base;
    }

    private JsonObject getJson(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", "Killer560sMod-RNGMeter/1.0")
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                return null;
            }
            return JsonParser.parseString(response.body()).getAsJsonObject();
        } catch (Exception e) {
            return null;
        }
    }
}
