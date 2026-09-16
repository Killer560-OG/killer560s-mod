package com.killer560.hub.goldorfrenzy;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted Goldor Frenzy Timer settings - see {@link GoldorFrenzyFeature}. Ships disabled by default. */
public final class GoldorFrenzyConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-goldorfrenzy.json");

    private static GoldorFrenzyConfig instance;

    private boolean enabled = false;
    /** Devonian's own {@code showTotal} switch (default false): show how long P3 has been running instead of
     *  the countdown to the next tick. */
    private boolean showTotal = false;
    /** Devonian counts down the ~5s gap between Storm's death line and Goldor's taunt as its own countdown
     *  (its {@code preGoldorTicks = 100}). On by default here - it is the part of the feature that tells you
     *  when to be standing at your terminal. */
    private boolean showPreGoldor = true;
    /** Ticks instead of seconds, matching {@code TickTimersConfig#isDisplayInTicks}. */
    private boolean displayInTicks = false;
    private boolean showPrefix = true;

    private GoldorFrenzyConfig() {
    }

    public static GoldorFrenzyConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new GoldorFrenzyConfig();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            GoldorFrenzyConfig cfg = new GoldorFrenzyConfig();
            cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
            cfg.showTotal = ConfigJson.getBool(obj, "showTotal", false);
            cfg.showPreGoldor = ConfigJson.getBool(obj, "showPreGoldor", true);
            cfg.displayInTicks = ConfigJson.getBool(obj, "displayInTicks", false);
            cfg.showPrefix = ConfigJson.getBool(obj, "showPrefix", true);
            instance = cfg;
        } catch (Exception e) {
            instance = new GoldorFrenzyConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("showTotal", showTotal);
            obj.addProperty("showPreGoldor", showPreGoldor);
            obj.addProperty("displayInTicks", displayInTicks);
            obj.addProperty("showPrefix", showPrefix);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public boolean isEnabled() {
        return enabled && com.killer560.hub.util.SkyblockGate.allows();
    }

    /** Raw saved value, ignoring the Skyblock gate - for the settings GUI's own button label. */
    public boolean isEnabledRaw() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isShowTotal() {
        return showTotal;
    }

    public void setShowTotal(boolean showTotal) {
        this.showTotal = showTotal;
    }

    public boolean isShowPreGoldor() {
        return showPreGoldor;
    }

    public void setShowPreGoldor(boolean showPreGoldor) {
        this.showPreGoldor = showPreGoldor;
    }

    public boolean isDisplayInTicks() {
        return displayInTicks;
    }

    public void setDisplayInTicks(boolean displayInTicks) {
        this.displayInTicks = displayInTicks;
    }

    public boolean isShowPrefix() {
        return showPrefix;
    }

    public void setShowPrefix(boolean showPrefix) {
        this.showPrefix = showPrefix;
    }
}
