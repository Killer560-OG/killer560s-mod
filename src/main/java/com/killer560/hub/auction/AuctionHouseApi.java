package com.killer560.hub.auction;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.croesus.DungeonChestValuer;
import com.killer560.hub.itembrowser.SkyblockItemEntry;
import com.killer560.hub.itembrowser.SkyblockItemRepository;
import com.killer560.hub.itembrowser.SkyblockItemStackFactory;
import com.killer560.hub.profileviewer.item.LegacyItems;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Real Hypixel Auction House BIN scanner - killer560's item 8.1 ("Custom AH search/sell menu... prices
 * come from a background cached scan PLUS a manual refresh button").
 * <p>
 * <b>Endpoint</b>: {@code https://api.hypixel.net/skyblock/auctions} (paginated, keyless - no API key
 * needed or sent). NOT {@code /v2/skyblock/auctions} - this repo already has a real, working AH scanner
 * ({@link com.killer560.hub.rngmeter.HypixelMarketPrices}) using this exact same un-versioned path;
 * reused here rather than re-guessing the shape. Each page returns {@code success}, {@code page},
 * {@code totalPages}, {@code totalAuctions}, {@code lastUpdated}, and an {@code auctions} array; each
 * auction has (real fields actually used below) {@code uuid}, {@code auctioneer} (both 32-char hex,
 * WITHOUT dashes), {@code item_name}, {@code tier}, {@code category}, {@code starting_bid}, {@code end}
 * (epoch millis), {@code bin} (true = buy-it-now, what this mod only ever shows), {@code claimed}, and
 * {@code item_bytes} (base64 gzip-compressed real Minecraft item NBT).
 * <p>
 * <b>Pacing</b>: per the brief, this pages through the (dozens of pages, tens of thousands of listings)
 * scan slowly and sequentially on one dedicated background thread - never concurrently and never on the
 * render/client-tick thread - with a real {@link #PAGE_DELAY_MS} pause between each page fetch, unlike
 * {@code HypixelMarketPrices}'s own concurrent-batch approach. A full scan is slow (well under the
 * {@link #AUTO_RESCAN_MINUTES}-minute schedule) but never blocks anything else.
 * <p>
 * <b>Icons</b>: a listing scanned live decodes its own real {@code item_bytes} via
 * {@link LegacyItems#decodeBase64} - the exact real item (skin, reforge, stars, enchants). A listing that
 * only came from the on-disk cache (before this session's first live scan finishes) instead resolves its
 * icon from {@link SkyblockItemRepository}/{@link SkyblockItemStackFactory} by its cached
 * {@code skyblockId} - a catalog-accurate approximation, not the exact real item, exactly per the brief's
 * "reuse SkyblockItemRepository and SkyblockItemStackFactory for item names, icons and tiers" instruction
 * for anything that isn't a fresh live decode.
 */
public final class AuctionHouseApi {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-auctionhouse");

    private static final String AUCTIONS_URL = "https://api.hypixel.net/skyblock/auctions";
    private static final Path CACHE_PATH = FabricLoader.getInstance().getConfigDir().resolve("killer560smod-auction-cache.json");
    private static final long AUTO_RESCAN_MINUTES = 5;
    /** Pause between each page fetch during a scan - "page through it slowly and never block". */
    private static final long PAGE_DELAY_MS = 250;
    /** Hard safety cap so a Hypixel response reporting a bogus totalPages can't scan forever. */
    private static final int MAX_PAGES = 400;

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(daemon("killer560smod-auction-schedule"));
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(daemon("killer560smod-auction-scan"));

    /** Known Ultimate enchant display names (Hypixel's own real in-game text), longest first so e.g.
     *  "Ultimate Wise" isn't cut short by a shorter prefix match. Not exhaustive of every possible future
     *  Ultimate enchant, but every one live on Hypixel as of this scan's design - a real one this list
     *  misses would simply not be recognized as an Ultimate enchant rather than mis-tagged as one. */
    private static final List<String> ULTIMATE_ENCHANT_NAMES = List.of(
            "Ultimate Wise", "Ultimate Chimera", "Ultimate No Pain No Gain", "Ultimate One For All",
            "Ultimate Rend", "Ultimate Soul Eater", "Ultimate Last Stand", "Ultimate Legion",
            "Ultimate Bank", "Ultimate Combo", "Ultimate Fatal Tempo", "Ultimate Flash",
            "Ultimate Habanero Tactics", "Ultimate Inferno", "Ultimate Jerry's Workshop",
            "Ultimate Swarm", "Ultimate The One", "Ultimate Wisdom", "Ultimate Duplex",
            "Ultimate Refrigerate", "Ultimate Sharpshooter", "Ultimate Reiterate"
    );
    private static final Pattern PET_LEVEL = Pattern.compile("^\\[Lvl (\\d+)]");

    private static volatile List<AuctionListing> listings = List.of();
    private static volatile long lastScanFinishedMs = 0;
    private static volatile int lastScanTotalPages = 0;
    private static volatile int lastScanPagesDone = 0;
    private static volatile String lastScanError = null;
    private static final AtomicBoolean scanning = new AtomicBoolean(false);
    private static volatile boolean triedDiskCache = false;
    private static volatile boolean autoScanStarted = false;

    private AuctionHouseApi() {
    }

    private static java.util.concurrent.ThreadFactory daemon(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }

    /** Current cached listings - never blocks, may be stale or (on a totally fresh install before the
     *  first scan lands) empty. Starts the auto-scan/disk-cache-load on first real use. */
    public static List<AuctionListing> getListings() {
        ensureAutoScanStarted();
        return listings;
    }

    public static boolean isScanning() {
        return scanning.get();
    }

    public static long getLastScanFinishedMs() {
        return lastScanFinishedMs;
    }

    public static String getLastScanError() {
        return lastScanError;
    }

    /** 0-100, or -1 when not currently scanning / total unknown yet. */
    public static int getScanProgressPercent() {
        if (!scanning.get() || lastScanTotalPages <= 0) {
            return -1;
        }
        return Math.min(100, (int) (100L * lastScanPagesDone / lastScanTotalPages));
    }

    public static void ensureAutoScanStarted() {
        if (autoScanStarted) {
            return;
        }
        autoScanStarted = true;
        if (!triedDiskCache) {
            triedDiskCache = true;
            WORKER.execute(AuctionHouseApi::loadFromDisk);
        }
        runAutoScanCycle();
    }

    private static void runAutoScanCycle() {
        refreshAsync().whenComplete((v, e) ->
                SCHEDULER.schedule(AuctionHouseApi::runAutoScanCycle, AUTO_RESCAN_MINUTES, TimeUnit.MINUTES));
    }

    /** Manual refresh (the settings tab's "Refresh" button) and the auto-scan both funnel through here;
     *  {@link #scanning} guards against two scans running at once. */
    public static CompletableFuture<Void> refreshAsync() {
        if (!scanning.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(null);
        }
        lastScanPagesDone = 0;
        lastScanTotalPages = 0;
        lastScanError = null;
        CompletableFuture<Void> done = new CompletableFuture<>();
        WORKER.execute(() -> {
            try {
                scanAllPagesBlocking();
            } catch (Exception e) {
                lastScanError = e.getMessage() != null ? e.getMessage() : e.toString();
                LOGGER.warn("[AuctionHouse] Scan failed", e);
            } finally {
                scanning.set(false);
                lastScanFinishedMs = System.currentTimeMillis();
                done.complete(null);
            }
        });
        return done;
    }

    /** Runs entirely on {@link #WORKER} - real HTTP + real NBT decode of every BIN listing, sequential
     *  page-by-page with {@link #PAGE_DELAY_MS} between requests. Never touches the render/tick thread. */
    private static void scanAllPagesBlocking() throws IOException, InterruptedException {
        JsonObject first = getJson(pageUrl(0));
        if (first == null || !first.has("success") || !first.get("success").getAsBoolean()) {
            lastScanError = "Auction House fetch failed on page 0.";
            return;
        }
        int totalPages = Math.min(MAX_PAGES, first.has("totalPages") ? first.get("totalPages").getAsInt() : 1);
        lastScanTotalPages = Math.max(1, totalPages);
        List<AuctionListing> collected = new ArrayList<>(4096);
        collectPage(first, collected);
        lastScanPagesDone = 1;
        for (int page = 1; page < totalPages; page++) {
            Thread.sleep(PAGE_DELAY_MS);
            JsonObject json = getJson(pageUrl(page));
            if (json != null && json.has("auctions")) {
                collectPage(json, collected);
            }
            lastScanPagesDone = page + 1;
        }
        listings = List.copyOf(collected);
        saveToDisk(listings);
        LOGGER.info("[AuctionHouse] Scan finished: {} BIN listings across {} pages.", collected.size(), totalPages);
    }

    private static void collectPage(JsonObject page, List<AuctionListing> out) {
        JsonArray auctions = page.getAsJsonArray("auctions");
        if (auctions == null) {
            return;
        }
        long now = System.currentTimeMillis();
        for (JsonElement el : auctions) {
            try {
                AuctionListing listing = decode(el.getAsJsonObject(), now);
                if (listing != null) {
                    out.add(listing);
                }
            } catch (Exception e) {
                // One malformed auction entry must never abort the whole page.
            }
        }
    }

    private static AuctionListing decode(JsonObject a, long now) {
        if (!a.has("bin") || !a.get("bin").getAsBoolean()) {
            return null;
        }
        if (a.has("claimed") && a.get("claimed").getAsBoolean()) {
            return null;
        }
        long end = a.has("end") ? a.get("end").getAsLong() : 0;
        if (end > 0 && end < now) {
            return null;
        }
        UUID uuid = parseFlexibleUuid(getStr(a, "uuid"));
        if (uuid == null) {
            return null;
        }
        UUID auctioneer = parseFlexibleUuid(getStr(a, "auctioneer"));
        String itemName = ChatFormatting.stripFormatting(getStr(a, "item_name")).trim();
        if (itemName.isEmpty()) {
            itemName = "Unknown Item";
        }
        String tier = a.has("tier") ? a.get("tier").getAsString() : null;
        String category = a.has("category") ? a.get("category").getAsString() : null;
        long startingBid = a.has("starting_bid") ? a.get("starting_bid").getAsLong() : 0L;

        ItemStack icon = ItemStack.EMPTY;
        String skyblockId = "";
        List<String> lore = List.of();
        String ultimateName = null;
        int ultimateTier = 0;
        if (a.has("item_bytes") && a.get("item_bytes").isJsonPrimitive()) {
            List<ItemStack> decoded = LegacyItems.decodeBase64(a.get("item_bytes").getAsString());
            if (!decoded.isEmpty() && !decoded.get(0).isEmpty()) {
                ItemStack stack = decoded.get(0);
                icon = stack;
                skyblockId = LegacyItems.skyblockId(stack);
                lore = DungeonChestValuer.cleanLore(stack);
                int[] tierOut = new int[1];
                ultimateName = findUltimateEnchant(lore, tierOut);
                ultimateTier = tierOut[0];
            }
        }
        if (icon.isEmpty()) {
            // Decode failure never drops the listing - it just shows without a real icon/lore/enchant
            // info until (if ever) a later scan of the same auction succeeds.
            icon = new ItemStack(Items.PAPER);
        }
        int petLevel = parsePetLevel(itemName);
        return new AuctionListing(uuid, auctioneer, itemName, skyblockId, tier, category, startingBid, end,
                petLevel, ultimateName, ultimateTier, lore, icon);
    }

    /** {@code "[Lvl 100] Golden Dragon"} -&gt; 100. -1 when the name has no pet-level prefix. */
    static int parsePetLevel(String plainItemName) {
        Matcher m = PET_LEVEL.matcher(plainItemName);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    /** Scans the real decoded item's own lore text for one of {@link #ULTIMATE_ENCHANT_NAMES} followed by
     *  a roman-numeral tier (Hypixel only ever lets one Ultimate enchant sit in an item's single Ultimate
     *  slot at a time, so the first match is the only one there is). Lore text, not a guessed NBT
     *  enchant-id table, is the source of truth here - it's exactly what Hypixel itself displays, so
     *  there's nothing to get wrong about which internal id maps to which real enchant. */
    static String findUltimateEnchant(List<String> plainLoreLines, int[] tierOut) {
        for (String line : plainLoreLines) {
            String trimmed = line.trim();
            for (String name : ULTIMATE_ENCHANT_NAMES) {
                if (trimmed.startsWith(name + " ")) {
                    String romanPart = trimmed.substring(name.length()).trim();
                    Integer roman = DungeonChestValuer.roman(romanPart);
                    if (roman != null) {
                        tierOut[0] = roman;
                        return name;
                    }
                }
            }
        }
        tierOut[0] = 0;
        return null;
    }

    private static String getStr(JsonObject obj, String key) {
        return obj.has(key) && obj.get(key).isJsonPrimitive() ? obj.get(key).getAsString() : "";
    }

    /** Hypixel's real {@code uuid}/{@code auctioneer} fields are 32-char hex WITHOUT dashes; accepts a
     *  dashed one too just in case. */
    private static UUID parseFlexibleUuid(String s) {
        if (s == null) {
            return null;
        }
        String hex = s.replace("-", "");
        if (hex.length() != 32) {
            return null;
        }
        try {
            return new UUID(Long.parseUnsignedLong(hex.substring(0, 16), 16), Long.parseUnsignedLong(hex.substring(16), 16));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String pageUrl(int page) {
        return AUCTIONS_URL + "?page=" + page;
    }

    private static JsonObject getJson(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", "Killer560sMod-AuctionHouse/1.0")
                    .GET()
                    .build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                return null;
            }
            return JsonParser.parseString(response.body()).getAsJsonObject();
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- disk cache (summarized, no lore/full item)

    /** Runs on {@link #WORKER} only. Rebuilds each cached listing's icon from the real item catalog by
     *  its saved {@code skyblockId} (see the class doc) rather than caching the full item NBT, keeping the
     *  cache file small - accurate enough to populate the panel instantly on launch, replaced by the real
     *  decoded item the moment the first live scan of the session reaches that listing (or it falls out
     *  of the list if it sold/expired since). */
    private static void loadFromDisk() {
        if (!Files.exists(CACHE_PATH)) {
            return;
        }
        try {
            byte[] gz = Files.readAllBytes(CACHE_PATH);
            String json;
            try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gz))) {
                json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
            List<AuctionListing> cached = new ArrayList<>(arr.size());
            for (JsonElement el : arr) {
                JsonObject o = el.getAsJsonObject();
                UUID uuid = parseFlexibleUuid(getStr(o, "uuid"));
                if (uuid == null) {
                    continue;
                }
                UUID auctioneer = parseFlexibleUuid(getStr(o, "auctioneer"));
                String skyblockId = getStr(o, "skyblockId");
                ItemStack icon = iconForCachedId(skyblockId);
                cached.add(new AuctionListing(uuid, auctioneer, getStr(o, "itemName"), skyblockId,
                        o.has("tier") ? o.get("tier").getAsString() : null,
                        o.has("category") ? o.get("category").getAsString() : null,
                        o.has("startingBid") ? o.get("startingBid").getAsLong() : 0L,
                        o.has("end") ? o.get("end").getAsLong() : 0L,
                        o.has("petLevel") ? o.get("petLevel").getAsInt() : -1,
                        o.has("ultimateEnchantName") ? o.get("ultimateEnchantName").getAsString() : null,
                        o.has("ultimateEnchantTier") ? o.get("ultimateEnchantTier").getAsInt() : 0,
                        List.of(), icon));
            }
            if (!cached.isEmpty() && listings.isEmpty()) {
                listings = List.copyOf(cached);
            }
        } catch (Exception e) {
            LOGGER.warn("[AuctionHouse] Failed to read disk cache.", e);
        }
    }

    private static ItemStack iconForCachedId(String skyblockId) {
        if (skyblockId != null && !skyblockId.isEmpty()) {
            SkyblockItemEntry entry = SkyblockItemRepository.findById(skyblockId);
            if (entry != null) {
                return SkyblockItemStackFactory.build(entry);
            }
        }
        return new ItemStack(Items.PAPER);
    }

    private static void saveToDisk(List<AuctionListing> toSave) {
        try {
            Files.createDirectories(CACHE_PATH.getParent());
            JsonArray arr = new JsonArray();
            for (AuctionListing l : toSave) {
                JsonObject o = new JsonObject();
                o.addProperty("uuid", l.uuid().toString());
                if (l.auctioneer() != null) {
                    o.addProperty("auctioneer", l.auctioneer().toString());
                }
                o.addProperty("itemName", l.itemName());
                o.addProperty("skyblockId", l.skyblockId());
                if (l.tier() != null) {
                    o.addProperty("tier", l.tier());
                }
                if (l.category() != null) {
                    o.addProperty("category", l.category());
                }
                o.addProperty("startingBid", l.startingBid());
                o.addProperty("end", l.end());
                o.addProperty("petLevel", l.petLevel());
                if (l.ultimateEnchantName() != null) {
                    o.addProperty("ultimateEnchantName", l.ultimateEnchantName());
                    o.addProperty("ultimateEnchantTier", l.ultimateEnchantTier());
                }
                arr.add(o);
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
                gz.write(arr.toString().getBytes(StandardCharsets.UTF_8));
            }
            Files.write(CACHE_PATH, bos.toByteArray());
        } catch (Exception e) {
            LOGGER.warn("[AuctionHouse] Failed to write disk cache.", e);
        }
    }
}
