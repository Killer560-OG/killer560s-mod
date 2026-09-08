package com.killer560.hub.rngmeter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted RNG Meter settings: bazaar pricing mode and auto-refresh interval. The Hypixel API key
 *  used for Auction House lookups is no longer user-configurable here - see
 *  {@link HypixelApiKeyProvider}. */
public final class RngMeterConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-rngmeter.json");

    private static RngMeterConfig instance;

    /** false = use Bazaar instant-sell price (default); true = use instant-buy price. */
    private boolean useInstantBuyPrice = false;
    private int refreshIntervalMinutes = 10;
    private boolean enabled = true;

    private RngMeterConfig() {
    }

    public static RngMeterConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new RngMeterConfig();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            RngMeterConfig cfg = new RngMeterConfig();
            cfg.useInstantBuyPrice = obj.has("useInstantBuyPrice") && obj.get("useInstantBuyPrice").getAsBoolean();
            cfg.refreshIntervalMinutes = obj.has("refreshIntervalMinutes")
                    ? Math.max(1, obj.get("refreshIntervalMinutes").getAsInt()) : 10;
            cfg.enabled = !obj.has("enabled") || obj.get("enabled").getAsBoolean();
            instance = cfg;
        } catch (Exception e) {
            instance = new RngMeterConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("useInstantBuyPrice", useInstantBuyPrice);
            obj.addProperty("refreshIntervalMinutes", refreshIntervalMinutes);
            obj.addProperty("enabled", enabled);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public boolean isUseInstantBuyPrice() {
        return useInstantBuyPrice;
    }

    public void setUseInstantBuyPrice(boolean useInstantBuyPrice) {
        this.useInstantBuyPrice = useInstantBuyPrice;
    }

    public int getRefreshIntervalMinutes() {
        return refreshIntervalMinutes;
    }

    public void setRefreshIntervalMinutes(int refreshIntervalMinutes) {
        this.refreshIntervalMinutes = Math.max(1, refreshIntervalMinutes);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
