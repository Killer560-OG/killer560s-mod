package com.killer560.hub.auction.ah;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.auction.AuctionListing;
import com.killer560.hub.profileviewer.item.LegacyItems;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.ModNet;
import com.killer560.hub.util.ModPaths;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * What the unified Auction House knows about an item's market, all from Hypixel's public API and computed off the
 * render thread:
 * <ul>
 *   <li><b>Lowest BIN</b> and how many are listed, per SkyBlock id, rebuilt from every full scan of
 *       {@code /v2/skyblock/auctions} ({@code AuctionHouseApi}).</li>
 *   <li><b>Lowest-BIN history</b>: one point per hour per id (the lowest seen in that hour), the last 24 kept, so a
 *       sparkline needs no extra request.</li>
 *   <li><b>Recent sales</b> from {@code /v2/skyblock/auctions_ended} (the last minute's ended auctions: auction_id,
 *       seller, buyer, timestamp, price, bin, item_bytes - checked with curl 2026-10-07, 55 entries), polled once a minute
 *       and only while the Auction House is in use ({@link #markInUse}), last 20 per id.</li>
 * </ul>
 * History survives a restart in {@code killer560smod-auction-history.json} (gzip), written at most every ten minutes.
 */
public final class AhMarket {

    private static final Logger LOGGER = ModLog.get("killer560smod-auctionhouse");
    private static final String ENDED_URL = ModNet.url("hypixel", "https://api.hypixel.net/v2/skyblock/auctions_ended");
    private static final Path HISTORY_PATH = ModPaths.config("killer560smod-auction-history.json");

    private static final long HOUR_MS = 3_600_000L;
    private static final int HISTORY_POINTS = 24;
    private static final int SALES_KEPT = 20;
    private static final long ENDED_POLL_MS = 60_000L;
    private static final long IN_USE_MS = 10 * 60_000L;
    private static final long SAVE_EVERY_MS = 10 * 60_000L;

    /** One price at a time. */
    public record Point(long atMs, long price) {
    }

    /** Lowest BIN and listing counts of one scan. */
    public record Stats(long lowestBin, int binCount, int auctionCount) {
    }

    private static volatile Map<String, Stats> stats = Map.of();
    /** id -> hourly lowest-BIN points, oldest first. Guarded by {@code AhMarket.class}. */
    private static final Map<String, ArrayDeque<Point>> BIN_HISTORY = new HashMap<>();
    /** id -> recent sales, oldest first. Guarded by {@code AhMarket.class}. */
    private static final Map<String, ArrayDeque<Point>> SALES = new HashMap<>();
    private static final Set<String> SEEN_ENDED = new HashSet<>();

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "killer560smod-auction-market");
        t.setDaemon(true);
        return t;
    });
    private static final AtomicBoolean polling = new AtomicBoolean(false);
    private static volatile long lastUseMs;
    private static volatile long lastPollMs;
    private static volatile long lastSaveMs;
    private static volatile boolean loaded;
    private static volatile boolean dirty;

    private AhMarket() {
    }

    // ---- reading --------------------------------------------------------------------------------------------------

    public static Stats stats(String skyblockId) {
        return skyblockId == null || skyblockId.isEmpty() ? null : stats.get(skyblockId);
    }

    public static long lowestBin(String skyblockId) {
        Stats s = stats(skyblockId);
        return s == null || s.binCount() == 0 ? -1 : s.lowestBin();
    }

    public static synchronized List<Point> binHistory(String skyblockId) {
        ArrayDeque<Point> d = skyblockId == null ? null : BIN_HISTORY.get(skyblockId);
        return d == null ? List.of() : List.copyOf(d);
    }

    public static synchronized List<Point> sales(String skyblockId) {
        ArrayDeque<Point> d = skyblockId == null ? null : SALES.get(skyblockId);
        return d == null ? List.of() : List.copyOf(d);
    }

    // ---- writing --------------------------------------------------------------------------------------------------

    /** A finished scan (worker thread): rebuild lowest BIN per id and add this hour's history point. */
    public static void onScan(List<AuctionListing> listings) {
        ensureLoaded();
        Map<String, long[]> acc = new HashMap<>();
        for (AuctionListing l : listings) {
            if (l.skyblockId().isEmpty()) {
                continue;
            }
            long[] a = acc.computeIfAbsent(l.skyblockId(), k -> new long[]{Long.MAX_VALUE, 0, 0});
            if (l.bin()) {
                a[0] = Math.min(a[0], l.startingBid());
                a[1]++;
            } else {
                a[2]++;
            }
        }
        Map<String, Stats> out = new HashMap<>(acc.size() * 2);
        long now = System.currentTimeMillis();
        synchronized (AhMarket.class) {
            for (Map.Entry<String, long[]> e : acc.entrySet()) {
                long[] a = e.getValue();
                out.put(e.getKey(), new Stats(a[1] > 0 ? a[0] : -1, (int) a[1], (int) a[2]));
                if (a[1] > 0) {
                    addHourly(e.getKey(), now, a[0]);
                }
            }
            dirty = true;
        }
        stats = Map.copyOf(out);
        saveIfDue(now);
    }

    private static void addHourly(String id, long now, long price) {
        ArrayDeque<Point> d = BIN_HISTORY.computeIfAbsent(id, k -> new ArrayDeque<>());
        Point last = d.peekLast();
        if (last != null && last.atMs() / HOUR_MS == now / HOUR_MS) {
            if (price < last.price()) {
                d.pollLast();
                d.addLast(new Point(last.atMs(), price));
            }
            return;
        }
        d.addLast(new Point(now, price));
        while (d.size() > HISTORY_POINTS) {
            d.pollFirst();
        }
    }

    /** The AH is on screen (browser or reskin): keeps the ended-auctions poll alive for ten minutes. */
    public static void markInUse() {
        lastUseMs = System.currentTimeMillis();
    }

    /** Client tick: poll auctions_ended once a minute while the AH was used in the last ten minutes. */
    public static void tick() {
        long now = System.currentTimeMillis();
        if (now - lastUseMs > IN_USE_MS || now - lastPollMs < ENDED_POLL_MS || !polling.compareAndSet(false, true)) {
            return;
        }
        lastPollMs = now;
        WORKER.execute(() -> {
            try {
                ensureLoaded();
                String body = get(ENDED_URL);
                if (body != null) {
                    applyEnded(body);
                }
                saveIfDue(System.currentTimeMillis());
            } catch (Exception e) {
                LOGGER.warn("[AuctionHouse] ended-auctions poll failed: {}", e.toString());
            } finally {
                polling.set(false);
            }
        });
    }

    /** Parses one auctions_ended answer into the sales history; returns how many new sales were recorded. */
    public static int applyEnded(String body) {
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        JsonArray arr = root.has("auctions") && root.get("auctions").isJsonArray() ? root.getAsJsonArray("auctions") : null;
        if (arr == null) {
            return 0;
        }
        int added = 0;
        for (JsonElement el : arr) {
            try {
                JsonObject a = el.getAsJsonObject();
                String auctionId = a.has("auction_id") ? a.get("auction_id").getAsString() : "";
                long price = a.has("price") ? a.get("price").getAsLong() : 0;
                long at = a.has("timestamp") ? a.get("timestamp").getAsLong() : System.currentTimeMillis();
                if (auctionId.isEmpty() || price <= 0 || !a.has("item_bytes")) {
                    continue;
                }
                List<ItemStack> items = LegacyItems.decodeBase64(a.get("item_bytes").getAsString());
                String id = items.isEmpty() ? "" : LegacyItems.skyblockId(items.get(0));
                if (id.isEmpty()) {
                    continue;
                }
                int count = Math.max(1, items.get(0).getCount());
                synchronized (AhMarket.class) {
                    if (!SEEN_ENDED.add(auctionId)) {
                        continue;
                    }
                    if (SEEN_ENDED.size() > 5000) {
                        SEEN_ENDED.clear();
                        SEEN_ENDED.add(auctionId);
                    }
                    ArrayDeque<Point> d = SALES.computeIfAbsent(id, k -> new ArrayDeque<>());
                    d.addLast(new Point(at, price / count));
                    while (d.size() > SALES_KEPT) {
                        d.pollFirst();
                    }
                    dirty = true;
                }
                added++;
            } catch (Exception ignored) {
                // one malformed entry never drops the rest
            }
        }
        return added;
    }

    private static String get(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
                    .header("User-Agent", "Killer560sMod-AuctionHouse/1.0").GET().build();
            HttpResponse<String> response = ModNet.send(HTTP, request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() / 100 == 2 ? response.body() : null;
        } catch (Exception e) {
            return null;
        }
    }

    // ---- persistence ----------------------------------------------------------------------------------------------

    private static synchronized void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        if (!Files.exists(HISTORY_PATH)) {
            return;
        }
        try {
            byte[] gz = Files.readAllBytes(HISTORY_PATH);
            String json;
            try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gz))) {
                json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            readSeries(root.getAsJsonObject("bin"), BIN_HISTORY, HISTORY_POINTS);
            readSeries(root.getAsJsonObject("sales"), SALES, SALES_KEPT);
        } catch (Exception e) {
            LOGGER.warn("[AuctionHouse] price history unreadable, starting fresh: {}", e.toString());
        }
    }

    private static void readSeries(JsonObject o, Map<String, ArrayDeque<Point>> into, int max) {
        if (o == null) {
            return;
        }
        for (Map.Entry<String, JsonElement> e : o.entrySet()) {
            JsonArray arr = e.getValue().getAsJsonArray();
            ArrayDeque<Point> d = new ArrayDeque<>();
            for (int i = 0; i + 1 < arr.size(); i += 2) {
                d.addLast(new Point(arr.get(i).getAsLong(), arr.get(i + 1).getAsLong()));
            }
            while (d.size() > max) {
                d.pollFirst();
            }
            if (!d.isEmpty()) {
                into.put(e.getKey(), d);
            }
        }
    }

    private static void saveIfDue(long now) {
        if (!dirty || now - lastSaveMs < SAVE_EVERY_MS) {
            return;
        }
        lastSaveMs = now;
        JsonObject root = new JsonObject();
        synchronized (AhMarket.class) {
            root.add("bin", writeSeries(BIN_HISTORY));
            root.add("sales", writeSeries(SALES));
            dirty = false;
        }
        try {
            Files.createDirectories(HISTORY_PATH.getParent());
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
                gz.write(root.toString().getBytes(StandardCharsets.UTF_8));
            }
            Files.write(HISTORY_PATH, bos.toByteArray());
        } catch (Exception e) {
            LOGGER.warn("[AuctionHouse] could not write price history: {}", e.toString());
        }
    }

    private static JsonObject writeSeries(Map<String, ArrayDeque<Point>> series) {
        JsonObject o = new JsonObject();
        for (Map.Entry<String, ArrayDeque<Point>> e : series.entrySet()) {
            JsonArray arr = new JsonArray();
            for (Point p : e.getValue()) {
                arr.add(p.atMs());
                arr.add(p.price());
            }
            o.add(e.getKey(), arr);
        }
        return o;
    }

    // ---- testkit --------------------------------------------------------------------------------------------------

    /** Adds a history point for {@code id} at {@code atMs} as a scan in that hour would (testkit sparkline data). */
    public static synchronized void addHistoryForTest(String id, long atMs, long price) {
        addHourly(id, atMs, price);
    }

    public static synchronized List<Point> salesForTest(String id) {
        return sales(id);
    }

    /** Ids with any recorded sale. */
    public static synchronized List<String> salesIdsForTest() {
        return new ArrayList<>(SALES.keySet());
    }
}
