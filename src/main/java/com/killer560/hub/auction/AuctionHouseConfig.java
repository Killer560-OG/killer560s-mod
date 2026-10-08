package com.killer560.hub.auction;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Settings of the unified Auction House (killer560, 2026-10-07: "redo the auction house in a similar manner" to the
 * Bazaar): the API-drawn browser ({@code auction/screen/AuctionHouseScreen}) and Hypixel's real AH menus drawn in the
 * same look ({@code auction/ah/AhReskin}). Kept out of {@link AuctionConfig}, which the Bazaar shares, in its own file
 * {@code killer560smod-auction-house.json} (the {@code auction} row of {@code ModPaths}: skyblock/auction).
 * <p>
 * The older AH settings that already lived in {@link AuctionConfig} stay there and keep their meaning: the feature
 * toggle, the {@code /ah} override, the open key, sort, rarity and category.
 */
public final class AuctionHouseConfig {

    /** BIN filter of the API browser. */
    public enum TypeFilter {
        ALL("All"),
        BIN("BIN only"),
        AUCTION("Auctions only");

        public final String label;

        TypeFilter(String label) {
            this.label = label;
        }

        public TypeFilter next() {
            TypeFilter[] all = values();
            return all[(ordinal() + 1) % all.length];
        }

        public TypeFilter previous() {
            TypeFilter[] all = values();
            return all[(ordinal() + all.length - 1) % all.length];
        }
    }

    /** A listing he opened, for the "Recently viewed" rail. */
    public record Viewed(String skyblockId, String name, String tier, String uuid, long atMs) {
    }

    public static final int MAX_RECENT = 8;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-auction-house.json");

    private static AuctionHouseConfig instance;

    /** Draw Hypixel's real AH menus in the unified look. */
    private boolean reskinRealAh = true;
    /** Held, shows Hypixel's own AH menu instead of the reskin. Left Alt by default; -1 = none. */
    private int vanillaKeyCode = 342;
    private TypeFilter typeFilter = TypeFilter.BIN;
    /** Price range of the API browser, coins; 0 = no bound. */
    private long minPrice = 0;
    private long maxPrice = 0;
    /** Track recent searches and viewed listings for the left rail. */
    private boolean trackRecents = true;
    private final List<String> recentSearches = new ArrayList<>();
    private final List<Viewed> recentViewed = new ArrayList<>();

    private AuctionHouseConfig() {
    }

    public static synchronized AuctionHouseConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static synchronized void load() {
        AuctionHouseConfig cfg = new AuctionHouseConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
                cfg.reskinRealAh = ConfigJson.getBool(obj, "reskinRealAh", true);
                cfg.vanillaKeyCode = ConfigJson.getInt(obj, "vanillaKeyCode", 342);
                cfg.typeFilter = ConfigJson.getEnum(obj, "typeFilter", TypeFilter.class, TypeFilter.BIN);
                cfg.minPrice = Math.max(0, ConfigJson.getLong(obj, "minPrice", 0));
                cfg.maxPrice = Math.max(0, ConfigJson.getLong(obj, "maxPrice", 0));
                cfg.trackRecents = ConfigJson.getBool(obj, "trackRecents", true);
                JsonArray searches = ConfigJson.getArray(obj, "recentSearches");
                if (searches != null) {
                    for (JsonElement e : searches) {
                        if (e.isJsonPrimitive() && cfg.recentSearches.size() < MAX_RECENT) {
                            String s = e.getAsString().trim();
                            if (!s.isEmpty() && s.length() <= 64) {
                                cfg.recentSearches.add(s);
                            }
                        }
                    }
                }
                JsonArray viewed = ConfigJson.getArray(obj, "recentViewed");
                if (viewed != null) {
                    for (JsonElement e : viewed) {
                        if (e.isJsonObject() && cfg.recentViewed.size() < MAX_RECENT) {
                            JsonObject o = e.getAsJsonObject();
                            String name = ConfigJson.getString(o, "name", "");
                            if (!name.isEmpty()) {
                                cfg.recentViewed.add(new Viewed(ConfigJson.getString(o, "id", ""), name,
                                        ConfigJson.getString(o, "tier", ""), ConfigJson.getString(o, "uuid", ""),
                                        ConfigJson.getLong(o, "at", 0)));
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
                // Keep defaults for this session on a malformed file - never throw out of a config load.
            }
        }
        instance = cfg;
    }

    public synchronized void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("reskinRealAh", reskinRealAh);
            obj.addProperty("vanillaKeyCode", vanillaKeyCode);
            obj.addProperty("typeFilter", typeFilter.name());
            obj.addProperty("minPrice", minPrice);
            obj.addProperty("maxPrice", maxPrice);
            obj.addProperty("trackRecents", trackRecents);
            JsonArray searches = new JsonArray();
            recentSearches.forEach(searches::add);
            obj.add("recentSearches", searches);
            JsonArray viewed = new JsonArray();
            for (Viewed v : recentViewed) {
                JsonObject o = new JsonObject();
                o.addProperty("id", v.skyblockId());
                o.addProperty("name", v.name());
                o.addProperty("tier", v.tier());
                o.addProperty("uuid", v.uuid());
                o.addProperty("at", v.atMs());
                viewed.add(o);
            }
            obj.add("recentViewed", viewed);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    /** Reskin Real Auction House, honouring the feature toggle and the Skyblock-Only gate. */
    public synchronized boolean isReskinRealAh() {
        // Part of the Auction House feature: its master toggle (Auction House Browser) turns the reskin off too.
        return reskinRealAh && AuctionConfig.getInstance().isAhEnabledRaw() && com.killer560.hub.util.SkyblockGate.allows();
    }

    public synchronized boolean isReskinRealAhRaw() {
        return reskinRealAh;
    }

    public synchronized void setReskinRealAh(boolean v) {
        reskinRealAh = v;
    }

    public synchronized int getVanillaKeyCode() {
        return vanillaKeyCode;
    }

    public synchronized void setVanillaKeyCode(int v) {
        vanillaKeyCode = v;
    }

    public synchronized TypeFilter getTypeFilter() {
        return typeFilter;
    }

    public synchronized void setTypeFilter(TypeFilter v) {
        typeFilter = v == null ? TypeFilter.BIN : v;
    }

    public synchronized long getMinPrice() {
        return minPrice;
    }

    public synchronized void setMinPrice(long v) {
        minPrice = Math.max(0, v);
    }

    public synchronized long getMaxPrice() {
        return maxPrice;
    }

    public synchronized void setMaxPrice(long v) {
        maxPrice = Math.max(0, v);
    }

    public synchronized boolean isTrackRecents() {
        return trackRecents;
    }

    public synchronized void setTrackRecents(boolean v) {
        trackRecents = v;
        if (!v) {
            recentSearches.clear();
            recentViewed.clear();
        }
    }

    public synchronized List<String> getRecentSearches() {
        return List.copyOf(recentSearches);
    }

    public synchronized List<Viewed> getRecentViewed() {
        return List.copyOf(recentViewed);
    }

    /** Most recent first; a repeat moves to the top (case-insensitive); at most {@link #MAX_RECENT}. */
    public synchronized void addRecentSearch(String query) {
        if (!trackRecents || query == null) {
            return;
        }
        String q = query.trim();
        if (q.isEmpty() || q.length() > 64) {
            return;
        }
        recentSearches.removeIf(s -> s.toLowerCase(Locale.ROOT).equals(q.toLowerCase(Locale.ROOT)));
        recentSearches.add(0, q);
        while (recentSearches.size() > MAX_RECENT) {
            recentSearches.remove(recentSearches.size() - 1);
        }
    }

    /** Most recent first; the same item (by id, else by name) moves to the top. */
    public synchronized void addRecentViewed(Viewed v) {
        if (!trackRecents || v == null || v.name() == null || v.name().isBlank()) {
            return;
        }
        recentViewed.removeIf(o -> !v.skyblockId().isEmpty() ? v.skyblockId().equals(o.skyblockId())
                : v.name().equals(o.name()));
        recentViewed.add(0, v);
        while (recentViewed.size() > MAX_RECENT) {
            recentViewed.remove(recentViewed.size() - 1);
        }
    }

    public synchronized void clearRecents() {
        recentSearches.clear();
        recentViewed.clear();
    }
}
