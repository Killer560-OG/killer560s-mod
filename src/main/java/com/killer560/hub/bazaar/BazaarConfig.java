package com.killer560.hub.bazaar;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The unified Bazaar screen's own settings (the older Bazaar settings - browser on/off, Reskin Real Bazaar, the keys,
 * order tracking, sort - stay in {@code auction.AuctionConfig}, which this package reads and writes through its
 * setters).
 * <ul>
 *   <li>{@link #followUpClick}: after HIS click on a product (or a bottom-bar button) the screen sends Hypixel's
 *       {@code /bz <name>} and, when exactly one search result is that product, clicks it once for him - the direct
 *       follow-up to his own click (killer560, 2026-10-07: "it's not automation, just a different way of making the
 *       menu"). ON by default; off, the results arrive with the product highlighted and he clicks it.</li>
 *   <li>{@link #hideHuds}: no HUD layer (bars, scoreboard, map, timers, overlays) draws while the Bazaar screen is
 *       open (killer560, 2026-10-07: "In the bazaar menu have it hide all other HUDs"). ON by default.</li>
 * </ul>
 */
public final class BazaarConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-bazaar.json");

    private static BazaarConfig instance;

    private boolean followUpClick = true;
    private boolean hideHuds = true;

    private BazaarConfig() {
    }

    public static synchronized BazaarConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static synchronized void load() {
        BazaarConfig cfg = new BazaarConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8))
                        .getAsJsonObject();
                cfg.followUpClick = ConfigJson.getBool(obj, "followUpClick", true);
                cfg.hideHuds = ConfigJson.getBool(obj, "hideHuds", true);
            } catch (Exception ignored) {
                cfg = new BazaarConfig();
            }
        }
        instance = cfg;
    }

    public synchronized void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("followUpClick", followUpClick);
            obj.addProperty("hideHuds", hideHuds);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public synchronized boolean isFollowUpClick() {
        return followUpClick;
    }

    public synchronized void setFollowUpClick(boolean v) {
        followUpClick = v;
    }

    public synchronized boolean isHideHuds() {
        return hideHuds;
    }

    public synchronized void setHideHuds(boolean v) {
        hideHuds = v;
    }
}
