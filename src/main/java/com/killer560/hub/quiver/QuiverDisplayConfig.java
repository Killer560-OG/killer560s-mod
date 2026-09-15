package com.killer560.hub.quiver;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted Quiver Display settings - see {@link QuiverDisplayFeature}'s class doc for the real
 *  Odin-ported lore-reading this is built on. Ships disabled by default, same as every other new
 *  feature in this mod. */
public final class QuiverDisplayConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-quiverdisplay.json");

    private static QuiverDisplayConfig instance;

    private boolean enabled = false;
    private boolean showName = true;

    private QuiverDisplayConfig() {
    }

    public static QuiverDisplayConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new QuiverDisplayConfig();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            QuiverDisplayConfig cfg = new QuiverDisplayConfig();
            cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
            cfg.showName = ConfigJson.getBool(obj, "showName", true);
            instance = cfg;
        } catch (Exception e) {
            instance = new QuiverDisplayConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("showName", showName);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isShowName() {
        return showName;
    }

    public void setShowName(boolean showName) {
        this.showName = showName;
    }
}
