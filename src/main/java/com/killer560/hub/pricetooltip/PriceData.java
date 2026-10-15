package com.killer560.hub.pricetooltip;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.ModNet;
import com.killer560.hub.util.ModPaths;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Every price and item fact the Price Tooltip reads, fetched off the render thread and swapped in as immutable maps,
 * so a tooltip only ever does map lookups (no JSON, no I/O).
 *
 * <p>Sources, all keyless (research 2026-10-15, see docs/FEATURES.md):
 * <ul>
 *   <li>Hypixel {@code /v2/resources/skyblock/items} ({@code hypixel}): {@code npc_sell_price},
 *       {@code museum_data.category} / {@code armor_set_donation_xp}, {@code upgrade_costs} (essence and items per
 *       star) and {@code gemstone_slots[].costs} (slot unlocks). Refetched every 12 hours.</li>
 *   <li>Hypixel {@code /v2/skyblock/bazaar} ({@code hypixel}): {@code quick_status.buyPrice/sellPrice}.</li>
 *   <li>Skyblocker's public price API ({@code hysky}): {@code /api/auctions/lowestbins} and
 *       {@code /api/auctions/lowestbins/average?days=1|3}, flat {@code id -> coins} maps in Skyblocker's id format
 *       ({@code LVL_1_LEGENDARY_GOLDEN_DRAGON}, {@code MUSIC_RUNE_3}, {@code SHINY_HYPERION}). moulberry.codes, the
 *       other well-known source, answered 525 on 2026-10-15.</li>
 *   <li>Coflnet's NEU-format list ({@code coflnet}, already used by the Profile Viewer) only as the lowest-BIN
 *       fallback when hysky fails; its pet keys ({@code TYPE;4}) are rewritten to the hysky form.</li>
 * </ul>
 * Prices refresh every {@link PriceTooltipConfig#getRefreshMinutes()} minutes, and only while the feature is on and a
 * tooltip asked for one in the last 15 minutes. The last good copy of everything is kept on disk in
 * {@code killer560smod-pricetooltip-cache/} (left out of profiles), so prices show the moment the game starts.
 *
 * <p>Every URL is resolved through {@link ModNet#url} at fetch time, not class load, so a harness can point a service at
 * an in-process fake mid-run.
 */
public final class PriceData {

    private static final Logger LOGGER = ModLog.get("killer560smod-pricetooltip");

    static final String ITEMS_URL = "https://api.hypixel.net/v2/resources/skyblock/items";
    static final String BAZAAR_URL = "https://api.hypixel.net/v2/skyblock/bazaar";
    static final String LBIN_URL = "https://hysky.de/api/auctions/lowestbins";
    static final String AVG_URL = "https://hysky.de/api/auctions/lowestbins/average?days=";
    static final String COFL_URL = "https://sky.coflnet.com/api/prices/neu";

    private static final long ITEMS_TTL_MS = 12L * 60 * 60 * 1000;
    private static final long IDLE_AFTER_MS = 15L * 60 * 1000;
    private static final long RETRY_MS = 60_000L;
    private static final Path CACHE_DIR = ModPaths.config("killer560smod-pricetooltip-cache");
    private static final Gson GSON = new Gson();

    /** One cost of a star or a gemstone slot: {@code coins}, or {@code amount} of {@code itemId}. */
    public record Cost(String itemId, int amount, double coins) {
    }

    /** One gemstone slot of an item, in Hypixel's order (the n-th slot of a type is {@code TYPE_n}). */
    public record GemSlot(String type, List<Cost> costs) {
    }

    /** Facts about one item id from the items resource. */
    public record ItemInfo(Double npcPrice, String museumCategory, List<String> museumSets,
                           List<List<Cost>> upgradeCosts, List<GemSlot> gemSlots) {
    }

    private static volatile Map<String, ItemInfo> items = Map.of();
    private static volatile Map<String, Double> bazaarBuy = Map.of();
    private static volatile Map<String, Double> bazaarSell = Map.of();
    private static volatile Map<String, Double> lowestBin = Map.of();
    private static volatile Map<String, Double> average1 = Map.of();
    private static volatile Map<String, Double> average3 = Map.of();

    private static final AtomicLong GENERATION = new AtomicLong();
    private static volatile long itemsFetchedAt = 0;
    private static volatile long pricesFetchedAt = 0;
    private static volatile long lastAttemptAt = 0;
    private static volatile long lastUseAt = 0;
    private static volatile String lastError = null;
    private static final AtomicBoolean refreshing = new AtomicBoolean();
    private static final AtomicBoolean started = new AtomicBoolean();

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final ScheduledExecutorService EXEC = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "killer560smod-pricetooltip");
        t.setDaemon(true);
        return t;
    });

    private PriceData() {
    }

    // ------------------------------------------------------------------ reads (any thread, cheap)

    /** Bumped whenever any map is swapped, so cached tooltip lines know to rebuild. */
    public static long generation() {
        return GENERATION.get();
    }

    public static ItemInfo item(String id) {
        return id == null ? null : items.get(id);
    }

    public static Double bazaarBuy(String id) {
        return id == null ? null : bazaarBuy.get(id);
    }

    public static Double bazaarSell(String id) {
        return id == null ? null : bazaarSell.get(id);
    }

    public static boolean isBazaarProduct(String id) {
        return id != null && (bazaarBuy.containsKey(id) || bazaarSell.containsKey(id));
    }

    public static Double lowestBin(String key) {
        return key == null ? null : lowestBin.get(key);
    }

    public static Double average(String key, int days) {
        return key == null ? null : (days <= 1 ? average1 : average3).get(key);
    }

    /**
     * What one unit of {@code id} costs to buy for Est. Item Value: the Bazaar's instant-buy price, else its
     * instant-sell price, else the lowest BIN, else the average. 0 when nothing knows it.
     */
    public static double unitPrice(String id) {
        if (id == null || id.isEmpty()) {
            return 0;
        }
        Double v = bazaarBuy.get(id);
        if (v == null || v <= 0) {
            v = bazaarSell.get(id);
        }
        if (v == null || v <= 0) {
            v = lowestBin.get(id);
        }
        if (v == null || v <= 0) {
            v = average3.get(id);
        }
        return v == null || v <= 0 ? 0 : v;
    }

    public static int itemCount() {
        return items.size();
    }

    public static int bazaarCount() {
        return bazaarBuy.size();
    }

    public static int lowestBinCount() {
        return lowestBin.size();
    }

    public static int averageCount(int days) {
        return (days <= 1 ? average1 : average3).size();
    }

    public static long pricesFetchedAt() {
        return pricesFetchedAt;
    }

    public static boolean isRefreshing() {
        return refreshing.get();
    }

    public static String lastError() {
        return lastError;
    }

    // ------------------------------------------------------------------ scheduling

    /**
     * Called by every tooltip that wants a price: notes the use and, the first time, loads the disk cache and starts
     * the refresh loop. Never blocks.
     */
    public static void touch() {
        lastUseAt = System.currentTimeMillis();
        if (started.compareAndSet(false, true)) {
            EXEC.execute(() -> {
                loadDiskCache();
                maybeRefresh();
            });
            EXEC.scheduleWithFixedDelay(PriceData::maybeRefresh, 30, 30, TimeUnit.SECONDS);
        }
    }

    private static void maybeRefresh() {
        try {
            PriceTooltipConfig cfg = PriceTooltipConfig.getInstance();
            long now = System.currentTimeMillis();
            if (!cfg.isEnabledRaw() || now - lastUseAt > IDLE_AFTER_MS) {
                return;
            }
            boolean pricesDue = now - pricesFetchedAt >= cfg.getRefreshMinutes() * 60_000L;
            boolean itemsDue = now - itemsFetchedAt >= ITEMS_TTL_MS;
            if ((pricesDue || itemsDue) && now - lastAttemptAt >= RETRY_MS) {
                refreshNow(itemsDue);
            }
        } catch (Throwable t) {
            LOGGER.warn("[PriceTooltip] refresh check failed: {}", t.toString());
        }
    }

    /** Asks for a refresh on the background thread now (the tab's Refresh button). */
    public static void refreshAsync() {
        touch();
        EXEC.execute(() -> refreshNow(System.currentTimeMillis() - itemsFetchedAt >= ITEMS_TTL_MS || items.isEmpty()));
    }

    /**
     * Fetches everything on the CALLING thread; true when at least one source answered. The background loop calls it;
     * the testkit calls it from its test thread against its fakes. Never call it on the render thread.
     */
    public static boolean refreshNow(boolean includeItems) {
        if (!refreshing.compareAndSet(false, true)) {
            return false;
        }
        lastAttemptAt = System.currentTimeMillis();
        boolean any = false;
        List<String> errors = new ArrayList<>();
        try {
            if (includeItems) {
                Map<String, ItemInfo> parsed = fetchItems(errors);
                if (parsed != null && !parsed.isEmpty()) {
                    items = parsed;
                    itemsFetchedAt = System.currentTimeMillis();
                    any = true;
                }
            }
            JsonObject bz = getJson(ModNet.url("hypixel", BAZAAR_URL), errors, "bazaar");
            if (bz != null && bz.has("products") && bz.get("products").isJsonObject()) {
                Map<String, Double> buy = new HashMap<>();
                Map<String, Double> sell = new HashMap<>();
                for (Map.Entry<String, JsonElement> e : bz.getAsJsonObject("products").entrySet()) {
                    if (!e.getValue().isJsonObject()) {
                        continue;
                    }
                    JsonElement qsEl = e.getValue().getAsJsonObject().get("quick_status");
                    if (qsEl == null || !qsEl.isJsonObject()) {
                        continue;
                    }
                    JsonObject qs = qsEl.getAsJsonObject();
                    buy.put(e.getKey(), num(qs.get("buyPrice")));
                    sell.put(e.getKey(), num(qs.get("sellPrice")));
                }
                if (!buy.isEmpty()) {
                    bazaarBuy = Collections.unmodifiableMap(buy);
                    bazaarSell = Collections.unmodifiableMap(sell);
                    any = true;
                }
            }
            Map<String, Double> lbin = flatMap(getJson(ModNet.url("hysky", LBIN_URL), errors, "lowest BIN"));
            if (lbin.isEmpty()) {
                lbin = fromNeu(flatMap(getJson(ModNet.url("coflnet", COFL_URL), errors, "lowest BIN (Coflnet)")));
            }
            if (!lbin.isEmpty()) {
                lowestBin = lbin;
                any = true;
            }
            Map<String, Double> a3 = flatMap(getJson(ModNet.url("hysky", AVG_URL + 3), errors, "3 day average"));
            if (!a3.isEmpty()) {
                average3 = a3;
                any = true;
            }
            if (PriceTooltipConfig.getInstance().getAverageDays() <= 1) {
                Map<String, Double> a1 = flatMap(getJson(ModNet.url("hysky", AVG_URL + 1), errors, "1 day average"));
                if (!a1.isEmpty()) {
                    average1 = a1;
                    any = true;
                }
            }
            if (any) {
                pricesFetchedAt = System.currentTimeMillis();
                GENERATION.incrementAndGet();
                saveDiskCache(includeItems);
            }
            lastError = errors.isEmpty() ? null : String.join("; ", errors);
            LOGGER.info("[PriceTooltip] refresh: {} items, {} bazaar, {} BIN, {} avg{}", items.size(), bazaarBuy.size(),
                    lowestBin.size(), average3.size(), errors.isEmpty() ? "" : " (failed: " + lastError + ")");
            return any;
        } catch (Throwable t) {
            lastError = t.toString();
            LOGGER.warn("[PriceTooltip] refresh failed: {}", t.toString());
            return false;
        } finally {
            refreshing.set(false);
        }
    }

    // ------------------------------------------------------------------ parsing

    private static Map<String, ItemInfo> fetchItems(List<String> errors) {
        JsonObject root = getJson(ModNet.url("hypixel", ITEMS_URL), errors, "items");
        if (root == null || !root.has("items") || !root.get("items").isJsonArray()) {
            return null;
        }
        return parseItems(root.getAsJsonArray("items"));
    }

    /** Hypixel's raw items array (or the cache's copy of the fields kept) to {@link ItemInfo}s. */
    static Map<String, ItemInfo> parseItems(JsonArray array) {
        Map<String, ItemInfo> out = new HashMap<>();
        for (JsonElement el : array) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject it = el.getAsJsonObject();
            String id = str(it, "id");
            if (id == null) {
                continue;
            }
            Double npc = it.has("npc_sell_price") ? num(it.get("npc_sell_price")) : null;
            String museumCat = null;
            List<String> sets = List.of();
            JsonObject md = obj(it, "museum_data");
            if (md != null) {
                museumCat = str(md, "category");
                JsonObject asd = obj(md, "armor_set_donation_xp");
                if (asd != null && !asd.isEmpty()) {
                    sets = List.copyOf(asd.keySet());
                }
            }
            List<List<Cost>> upgrades = List.of();
            if (it.has("upgrade_costs") && it.get("upgrade_costs").isJsonArray()) {
                List<List<Cost>> list = new ArrayList<>();
                for (JsonElement star : it.getAsJsonArray("upgrade_costs")) {
                    list.add(costs(star));
                }
                upgrades = List.copyOf(list);
            }
            List<GemSlot> slots = List.of();
            if (it.has("gemstone_slots") && it.get("gemstone_slots").isJsonArray()) {
                List<GemSlot> list = new ArrayList<>();
                for (JsonElement s : it.getAsJsonArray("gemstone_slots")) {
                    if (s.isJsonObject()) {
                        list.add(new GemSlot(str(s.getAsJsonObject(), "slot_type"), costs(s.getAsJsonObject().get("costs"))));
                    }
                }
                slots = List.copyOf(list);
            }
            if (npc == null && museumCat == null && upgrades.isEmpty() && slots.isEmpty()) {
                continue;
            }
            out.put(id, new ItemInfo(npc, museumCat, sets, upgrades, slots));
        }
        return Collections.unmodifiableMap(out);
    }

    private static List<Cost> costs(JsonElement el) {
        if (el == null || !el.isJsonArray()) {
            return List.of();
        }
        List<Cost> out = new ArrayList<>();
        for (JsonElement c : el.getAsJsonArray()) {
            if (!c.isJsonObject()) {
                continue;
            }
            JsonObject o = c.getAsJsonObject();
            String type = str(o, "type");
            int amount = o.has("amount") ? (int) num(o.get("amount")) : 1;
            if ("ESSENCE".equals(type)) {
                String ess = str(o, "essence_type");
                if (ess != null) {
                    out.add(new Cost("ESSENCE_" + ess, amount, 0));
                }
            } else if ("ITEM".equals(type)) {
                String item = str(o, "item_id");
                if (item != null) {
                    out.add(new Cost(item, amount, 0));
                }
            } else if ("COINS".equals(type)) {
                out.add(new Cost(null, 0, num(o.get("coins"))));
            }
        }
        return List.copyOf(out);
    }

    /** NEU ids (Coflnet) to the hysky form for the keys that differ: {@code GOLDEN_DRAGON;4} -> {@code LVL_1_LEGENDARY_GOLDEN_DRAGON}. */
    static Map<String, Double> fromNeu(Map<String, Double> neu) {
        if (neu.isEmpty()) {
            return neu;
        }
        String[] tiers = {"COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC"};
        Map<String, Double> out = new HashMap<>();
        for (Map.Entry<String, Double> e : neu.entrySet()) {
            String k = e.getKey();
            int semi = k.indexOf(';');
            if (semi > 0) {
                try {
                    int tier = Integer.parseInt(k.substring(semi + 1));
                    if (tier >= 0 && tier < tiers.length) {
                        out.put("LVL_1_" + tiers[tier] + "_" + k.substring(0, semi), e.getValue());
                    }
                } catch (NumberFormatException ignored) {
                    // Not a pet key.
                }
            } else {
                out.put(k, e.getValue());
            }
        }
        return Collections.unmodifiableMap(out);
    }

    private static Map<String, Double> flatMap(JsonObject json) {
        if (json == null) {
            return Map.of();
        }
        Map<String, Double> out = new HashMap<>();
        for (Map.Entry<String, JsonElement> e : json.entrySet()) {
            JsonElement v = e.getValue();
            if (v != null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber()) {
                double d = v.getAsDouble();
                if (d > 0) {
                    out.put(e.getKey(), d);
                }
            }
        }
        return Collections.unmodifiableMap(out);
    }

    private static JsonObject getJson(String url, List<String> errors, String what) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", "Killer560sMod-PriceTooltip/1.0")
                    .GET().build();
            HttpResponse<String> res = ModNet.send(HTTP, req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() / 100 != 2) {
                errors.add(what + " HTTP " + res.statusCode());
                return null;
            }
            JsonElement el = JsonParser.parseString(res.body());
            return el.isJsonObject() ? el.getAsJsonObject() : null;
        } catch (Exception e) {
            errors.add(what + " " + e.getClass().getSimpleName());
            return null;
        }
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    private static JsonObject obj(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    private static double num(JsonElement e) {
        try {
            return e == null || !e.isJsonPrimitive() ? 0 : e.getAsDouble();
        } catch (Exception ex) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ disk cache

    private static void saveDiskCache(boolean includeItems) {
        try {
            Files.createDirectories(CACHE_DIR);
            JsonObject prices = new JsonObject();
            prices.addProperty("fetchedAt", pricesFetchedAt);
            prices.add("bazaarBuy", GSON.toJsonTree(bazaarBuy));
            prices.add("bazaarSell", GSON.toJsonTree(bazaarSell));
            prices.add("lowestBin", GSON.toJsonTree(lowestBin));
            prices.add("average1", GSON.toJsonTree(average1));
            prices.add("average3", GSON.toJsonTree(average3));
            write(CACHE_DIR.resolve("prices.json"), GSON.toJson(prices));
            if (includeItems && !items.isEmpty()) {
                JsonObject wrap = new JsonObject();
                wrap.addProperty("fetchedAt", itemsFetchedAt);
                JsonArray arr = new JsonArray();
                for (Map.Entry<String, ItemInfo> e : items.entrySet()) {
                    arr.add(toRaw(e.getKey(), e.getValue()));
                }
                wrap.add("items", arr);
                write(CACHE_DIR.resolve("items.json"), GSON.toJson(wrap));
            }
        } catch (Exception e) {
            LOGGER.warn("[PriceTooltip] could not write the price cache: {}", e.toString());
        }
    }

    /** Back to Hypixel's own shape, so the cache goes through {@link #parseItems} like a live answer. */
    private static JsonObject toRaw(String id, ItemInfo info) {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        if (info.npcPrice() != null) {
            o.addProperty("npc_sell_price", info.npcPrice());
        }
        if (info.museumCategory() != null) {
            JsonObject md = new JsonObject();
            md.addProperty("category", info.museumCategory());
            if (!info.museumSets().isEmpty()) {
                JsonObject sets = new JsonObject();
                for (String s : info.museumSets()) {
                    sets.addProperty(s, 1);
                }
                md.add("armor_set_donation_xp", sets);
            }
            o.add("museum_data", md);
        }
        if (!info.upgradeCosts().isEmpty()) {
            JsonArray stars = new JsonArray();
            for (List<Cost> star : info.upgradeCosts()) {
                stars.add(rawCosts(star));
            }
            o.add("upgrade_costs", stars);
        }
        if (!info.gemSlots().isEmpty()) {
            JsonArray slots = new JsonArray();
            for (GemSlot s : info.gemSlots()) {
                JsonObject so = new JsonObject();
                so.addProperty("slot_type", s.type());
                so.add("costs", rawCosts(s.costs()));
                slots.add(so);
            }
            o.add("gemstone_slots", slots);
        }
        return o;
    }

    private static JsonArray rawCosts(List<Cost> costs) {
        JsonArray arr = new JsonArray();
        for (Cost c : costs) {
            JsonObject co = new JsonObject();
            if (c.itemId() == null) {
                co.addProperty("type", "COINS");
                co.addProperty("coins", c.coins());
            } else if (c.itemId().startsWith("ESSENCE_")) {
                co.addProperty("type", "ESSENCE");
                co.addProperty("essence_type", c.itemId().substring("ESSENCE_".length()));
                co.addProperty("amount", c.amount());
            } else {
                co.addProperty("type", "ITEM");
                co.addProperty("item_id", c.itemId());
                co.addProperty("amount", c.amount());
            }
            arr.add(co);
        }
        return arr;
    }

    private static void write(Path file, String text) throws Exception {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, text, StandardCharsets.UTF_8);
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private static void loadDiskCache() {
        try {
            Path itemsFile = CACHE_DIR.resolve("items.json");
            if (items.isEmpty() && Files.isRegularFile(itemsFile)) {
                JsonObject wrap = JsonParser.parseString(Files.readString(itemsFile, StandardCharsets.UTF_8)).getAsJsonObject();
                if (wrap.has("items") && wrap.get("items").isJsonArray()) {
                    items = parseItems(wrap.getAsJsonArray("items"));
                    itemsFetchedAt = (long) num(wrap.get("fetchedAt"));
                }
            }
            Path pricesFile = CACHE_DIR.resolve("prices.json");
            if (lowestBin.isEmpty() && Files.isRegularFile(pricesFile)) {
                JsonObject p = JsonParser.parseString(Files.readString(pricesFile, StandardCharsets.UTF_8)).getAsJsonObject();
                bazaarBuy = flatMap(obj(p, "bazaarBuy"));
                bazaarSell = flatMap(obj(p, "bazaarSell"));
                lowestBin = flatMap(obj(p, "lowestBin"));
                average1 = flatMap(obj(p, "average1"));
                average3 = flatMap(obj(p, "average3"));
                pricesFetchedAt = (long) num(p.get("fetchedAt"));
            }
            GENERATION.incrementAndGet();
        } catch (Exception e) {
            LOGGER.warn("[PriceTooltip] price cache unreadable, refetching: {}", e.toString());
        }
    }

    // ------------------------------------------------------------------ testkit

    /** Testkit: forget every price and fact (memory only), so a case starts from nothing. */
    public static void clearForTest() {
        items = Map.of();
        bazaarBuy = Map.of();
        bazaarSell = Map.of();
        lowestBin = Map.of();
        average1 = Map.of();
        average3 = Map.of();
        itemsFetchedAt = 0;
        pricesFetchedAt = 0;
        lastAttemptAt = 0;
        GENERATION.incrementAndGet();
    }

    /** Testkit: start the loop with no disk cache read, so a case's fakes are the only source. */
    public static void markStartedForTest() {
        started.set(true);
    }
}
