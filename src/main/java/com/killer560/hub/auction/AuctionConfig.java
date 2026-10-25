package com.killer560.hub.auction;

import com.killer560.hub.util.ModPaths;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persisted settings for killer560's item 8.1 - the custom Auction House browser, Bazaar browser, and
 * "Create BIN Auction" Listing Helper (see {@link AuctionHouseFeature}, {@code BazaarFeature},
 * {@code ListingHelperFeature}). One config file for all three sub-features since they're one requested
 * item, living in the New tab together. Every field here survives a restart.
 * <p>
 * Auction House and Bazaar ship ON by default (killer560, 2026-09-27: "Turn auction house and bazaar
 * settings on by default"); the Listing Helper and the {@code /ah} command override stay OFF, unrelated
 * asks with their own reasoning below.
 */
public final class AuctionConfig {

    /** Sort order for the AH browser's list - see {@code AuctionHouseScreen}. */
    public enum SortMode {
        PRICE_LOW("Price: Low to High"),
        PRICE_HIGH("Price: High to Low"),
        ENDING_SOONEST("Ending Soonest"),
        ULTIMATE_ENCHANT("Ultimate Enchant Tier");

        public final String label;

        SortMode(String label) {
            this.label = label;
        }

        public SortMode next() {
            SortMode[] all = values();
            return all[(ordinal() + 1) % all.length];
        }

        public SortMode previous() {
            SortMode[] all = values();
            return all[(all.length + ordinal() - 1) % all.length];
        }
    }

    /** Sort order for the Bazaar browser - see {@code BazaarScreen}. */
    public enum BazaarSortMode {
        BUY_PRICE_HIGH("Buy Price: High to Low"),
        SELL_PRICE_HIGH("Sell Price: High to Low"),
        SPREAD_HIGH("Spread: High to Low"),
        VOLUME_HIGH("Volume: High to Low");

        public final String label;

        BazaarSortMode(String label) {
            this.label = label;
        }
    }

    /** killer560, 2026-09-27: "make the ah viewer have an option to toggle between auctions and bins" -
     *  which half of {@code AuctionHouseApi}'s scanned listings {@code AuctionHouseScreen} shows. BIN is the
     *  default so an upgrade from an older config (missing this key) keeps showing exactly what it always
     *  showed before this existed. */
    public enum ListingMode {
        BIN("BINs"),
        AUCTION("Auctions");

        public final String label;

        ListingMode(String label) {
            this.label = label;
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-auction.json");

    private static AuctionConfig instance;

    // killer560, 2026-09-27: "Turn auction house and bazaar settings on by default."
    // killer560, 2026-10-11: "for the bazaar and ah overlay make it so the toggle to the mod is also the toggle to the
    // reskin not a separate toggle." Each is ONE switch: the API browser AND Hypixel's real menus drawn in its look.
    // The old reskinRealBazaar (this file) and reskinRealAh (killer560smod-auction-house.json) are read once by
    // load() - on if either was on - and never written again.
    private boolean ahEnabled = true;
    private boolean bazaarEnabled = true;
    private boolean listingHelperEnabled = false;
    /** killer560's item 8.1: "/ah replacement toggle" - OFF by default, real Hypixel /ah always still
     *  reachable via the explicit {@code /hypixelah} command regardless of this. */
    private boolean overrideAhCommand = false;
    /** The /bz override is gone (2026-10-07): killer560, "The custom bazaar menu should essentially fully replace the
     *  bazaar ... So the command /killer560bz should only work with a booster cookie." /bz is always Hypixel's own
     *  command now, and Hypixel's real Bazaar is drawn in this browser's look whenever {@link #bazaarEnabled} is on. */
    /** Held, shows Hypixel's own Bazaar menu instead of the reskin. Left Alt by default; -1 = none. */
    private int bazaarVanillaKeyCode = 342;
    private int openAhKeyCode = -1;
    private int openBazaarKeyCode = -1;
    // killer560, 2026-09-27: "have it default to low to high price."
    private SortMode lastSort = SortMode.PRICE_LOW;
    /** Last-picked rarity filter, uppercase Hypixel tier name (e.g. "LEGENDARY"), or "" for Any. */
    private String lastRarityFilter = "";
    private int lastMinPetLevel = 0;
    private BazaarSortMode lastBazaarSort = BazaarSortMode.VOLUME_HIGH;
    /** killer560, 2026-09-27: "toggle between auctions and bins" - which half of the scan {@code
     *  AuctionHouseScreen} shows. See {@link ListingMode}. */
    private ListingMode lastListingMode = ListingMode.BIN;
    /** Last-picked AH category rail filter (Hypixel's own real {@code category} string, e.g. "weapon"),
     *  or "" for "All" - see {@code AuctionHouseScreen}'s category rail. */
    private String lastCategoryFilter = "";
    /** Last-picked Bazaar category rail filter (from the shared item catalog's {@code category} field),
     *  or "" for "All" - see {@code BazaarScreen}'s category rail. */
    private String lastBazaarCategoryFilter = "";
    /** Read the player's own orders from Hypixel's Manage Orders menu for the browser's My Orders view (read only). */
    private boolean trackBazaarOrders = true;

    private AuctionConfig() {
    }

    public static synchronized AuctionConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static synchronized void load() {
        AuctionConfig cfg = new AuctionConfig();
        boolean migrated = false;
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
                // The fallback must be the FIELD's default, not false. These default to true (killer560,
                // 2026-09-27: "turn auction house and bazaar settings on by default") but load resolved a
                // missing or malformed key to false - so a config written before those keys existed, or one
                // with a typo'd key, silently turned both off and looked like the feature was broken.
                cfg.ahEnabled = ConfigJson.getBool(obj, "ahEnabled", true);
                cfg.bazaarEnabled = ConfigJson.getBool(obj, "bazaarEnabled", true);
                cfg.listingHelperEnabled = ConfigJson.getBool(obj, "listingHelperEnabled", false);
                cfg.overrideAhCommand = ConfigJson.getBool(obj, "overrideAhCommand", false);
                // One switch since 2026-10-11: a reskin that was on keeps the whole feature on.
                if (obj.has("reskinRealBazaar")) {
                    cfg.bazaarEnabled |= ConfigJson.getBool(obj, "reskinRealBazaar", false);
                    migrated = true;
                }
                cfg.bazaarVanillaKeyCode = ConfigJson.getInt(obj, "bazaarVanillaKeyCode", 342);
                cfg.openAhKeyCode = ConfigJson.getInt(obj, "openAhKeyCode", -1);
                cfg.openBazaarKeyCode = ConfigJson.getInt(obj, "openBazaarKeyCode", -1);
                cfg.lastSort = ConfigJson.getEnum(obj, "lastSort", SortMode.class, SortMode.PRICE_LOW);
                cfg.lastRarityFilter = ConfigJson.getString(obj, "lastRarityFilter", "");
                cfg.lastMinPetLevel = ConfigJson.getInt(obj, "lastMinPetLevel", 0);
                cfg.lastBazaarSort = ConfigJson.getEnum(obj, "lastBazaarSort", BazaarSortMode.class, BazaarSortMode.VOLUME_HIGH);
                cfg.lastListingMode = ConfigJson.getEnum(obj, "lastListingMode", ListingMode.class, ListingMode.BIN);
                cfg.lastCategoryFilter = ConfigJson.getString(obj, "lastCategoryFilter", "");
                cfg.lastBazaarCategoryFilter = ConfigJson.getString(obj, "lastBazaarCategoryFilter", "");
                cfg.trackBazaarOrders = ConfigJson.getBool(obj, "trackBazaarOrders", true);
            } catch (Exception ignored) {
                // Keep defaults for this session on a malformed file - never throw out of a config load.
            }
        }
        if (migrateAhReskin(cfg)) {
            migrated = true;
        }
        instance = cfg;
        if (migrated) {
            cfg.save(); // the old key is gone from disk, so a later "off" is not undone by the next load
        }
    }

    /**
     * Reskin Real Auction House lived in killer560smod-auction-house.json. If that file still has the key: an "on"
     * turns the Auction House on, and the key is removed from that file (the rest of it untouched).
     */
    private static boolean migrateAhReskin(AuctionConfig cfg) {
        Path ahPath = ModPaths.config("killer560smod-auction-house.json");
        try {
            if (!Files.exists(ahPath)) {
                return false;
            }
            JsonObject ah = JsonParser.parseString(Files.readString(ahPath, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!ah.has("reskinRealAh")) {
                return false;
            }
            cfg.ahEnabled |= ConfigJson.getBool(ah, "reskinRealAh", false);
            ah.remove("reskinRealAh");
            Files.writeString(ahPath, GSON.toJson(ah), StandardCharsets.UTF_8);
            return true;
        } catch (Exception ignored) {
            return false; // never throw out of a config load
        }
    }

    public synchronized void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("ahEnabled", ahEnabled);
            obj.addProperty("bazaarEnabled", bazaarEnabled);
            obj.addProperty("listingHelperEnabled", listingHelperEnabled);
            obj.addProperty("overrideAhCommand", overrideAhCommand);
            obj.addProperty("bazaarVanillaKeyCode", bazaarVanillaKeyCode);
            obj.addProperty("openAhKeyCode", openAhKeyCode);
            obj.addProperty("openBazaarKeyCode", openBazaarKeyCode);
            obj.addProperty("lastSort", lastSort.name());
            obj.addProperty("lastRarityFilter", lastRarityFilter);
            obj.addProperty("lastMinPetLevel", lastMinPetLevel);
            obj.addProperty("lastBazaarSort", lastBazaarSort.name());
            obj.addProperty("lastListingMode", lastListingMode.name());
            obj.addProperty("lastCategoryFilter", lastCategoryFilter);
            obj.addProperty("lastBazaarCategoryFilter", lastBazaarCategoryFilter);
            obj.addProperty("trackBazaarOrders", trackBazaarOrders);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public synchronized boolean isAhEnabled() {
        return ahEnabled && com.killer560.hub.util.SkyblockGate.allows();
    }

    /** Raw flag (ignores the Skyblock-Only gate) - used by settings UI so the toggle always reflects
     *  what's actually saved, same reasoning as every other {@code XyzConfig}'s tab. */
    public synchronized boolean isAhEnabledRaw() {
        return ahEnabled;
    }

    public synchronized void setAhEnabled(boolean v) {
        ahEnabled = v;
    }

    public synchronized boolean isBazaarEnabled() {
        return bazaarEnabled && com.killer560.hub.util.SkyblockGate.allows();
    }

    public synchronized boolean isBazaarEnabledRaw() {
        return bazaarEnabled;
    }

    public synchronized void setBazaarEnabled(boolean v) {
        bazaarEnabled = v;
    }

    public synchronized boolean isListingHelperEnabled() {
        return listingHelperEnabled && com.killer560.hub.util.SkyblockGate.allows();
    }

    public synchronized boolean isListingHelperEnabledRaw() {
        return listingHelperEnabled;
    }

    public synchronized void setListingHelperEnabled(boolean v) {
        listingHelperEnabled = v;
    }

    public synchronized boolean isOverrideAhCommand() {
        return overrideAhCommand;
    }

    public synchronized void setOverrideAhCommand(boolean v) {
        overrideAhCommand = v;
    }

    public synchronized int getBazaarVanillaKeyCode() {
        return bazaarVanillaKeyCode;
    }

    public synchronized void setBazaarVanillaKeyCode(int v) {
        bazaarVanillaKeyCode = v;
    }

    public synchronized int getOpenAhKeyCode() {
        return openAhKeyCode;
    }

    public synchronized void setOpenAhKeyCode(int v) {
        openAhKeyCode = v;
    }

    public synchronized int getOpenBazaarKeyCode() {
        return openBazaarKeyCode;
    }

    public synchronized void setOpenBazaarKeyCode(int v) {
        openBazaarKeyCode = v;
    }

    public synchronized SortMode getLastSort() {
        return lastSort;
    }

    public synchronized void setLastSort(SortMode v) {
        lastSort = v == null ? SortMode.PRICE_LOW : v;
    }

    public synchronized String getLastRarityFilter() {
        return lastRarityFilter;
    }

    public synchronized void setLastRarityFilter(String v) {
        lastRarityFilter = v == null ? "" : v;
    }

    public synchronized int getLastMinPetLevel() {
        return lastMinPetLevel;
    }

    public synchronized void setLastMinPetLevel(int v) {
        lastMinPetLevel = Math.max(0, Math.min(100, v));
    }

    public synchronized BazaarSortMode getLastBazaarSort() {
        return lastBazaarSort;
    }

    public synchronized void setLastBazaarSort(BazaarSortMode v) {
        lastBazaarSort = v == null ? BazaarSortMode.VOLUME_HIGH : v;
    }

    public synchronized ListingMode getLastListingMode() {
        return lastListingMode;
    }

    public synchronized void setLastListingMode(ListingMode v) {
        lastListingMode = v == null ? ListingMode.BIN : v;
    }

    public synchronized String getLastCategoryFilter() {
        return lastCategoryFilter;
    }

    public synchronized void setLastCategoryFilter(String v) {
        lastCategoryFilter = v == null ? "" : v;
    }

    public synchronized String getLastBazaarCategoryFilter() {
        return lastBazaarCategoryFilter;
    }

    public synchronized void setLastBazaarCategoryFilter(String v) {
        lastBazaarCategoryFilter = v == null ? "" : v;
    }

    public synchronized boolean isTrackBazaarOrders() {
        return trackBazaarOrders;
    }

    public synchronized void setTrackBazaarOrders(boolean v) {
        trackBazaarOrders = v;
    }
}
