package com.killer560.hub.mining.profit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.SkyblockGate;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Settings for the Mining Profit/Hour tracker ({@link MiningProfitTracker}) - split out from the tracker
 * itself the same way {@code runsummary.RunSummaryConfig} (settings) is split from
 * {@code runsummary.RunHistoryStore}/{@code RunSummaryFeature} (the actual running totals). Ships OFF, like
 * every other new Mining (WIP) feature.
 */
public final class MiningProfitConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-mining-profit-settings.json");

    private static MiningProfitConfig instance;

    private boolean enabled = false;
    /** Only counts items gained while standing on a mining island (Dwarven Mines / Glacite Tunnels /
     *  Crystal Hollows / Gold Mine / Deep Caverns) - see {@link MiningProfitTracker#isMiningIsland}. Turning
     *  this off tracks item gains anywhere on Skyblock instead (still paused off Skyblock/p3sim). */
    private boolean miningIslandsOnly = true;

    private MiningProfitConfig() {
    }

    public static MiningProfitConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new MiningProfitConfig();
            return;
        }
        try {
            JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
            MiningProfitConfig cfg = new MiningProfitConfig();
            cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
            cfg.miningIslandsOnly = ConfigJson.getBool(obj, "miningIslandsOnly", true);
            instance = cfg;
        } catch (Exception e) {
            instance = new MiningProfitConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("miningIslandsOnly", miningIslandsOnly);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public boolean isEnabled() {
        return enabled && SkyblockGate.allows();
    }

    public boolean isEnabledRaw() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isMiningIslandsOnly() {
        return miningIslandsOnly;
    }

    public void setMiningIslandsOnly(boolean v) {
        miningIslandsOnly = v;
    }
}
