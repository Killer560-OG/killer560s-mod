package com.killer560.hub.doorkeys;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted Door Keys settings - see {@link DoorKeysFeature}'s class doc for the real noamm-ported
 *  highlight this is built on. Ships disabled by default, same as every other new feature in this mod. */
public final class DoorKeysConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-doorkeys.json");

    private static DoorKeysConfig instance;

    private boolean enabled = false;
    private boolean highlightWither = true;
    private boolean highlightBlood = true;
    private boolean showTracer = true;

    private DoorKeysConfig() {
    }

    public static DoorKeysConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new DoorKeysConfig();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            DoorKeysConfig cfg = new DoorKeysConfig();
            cfg.enabled = ConfigJson.getBool(obj, "enabled", cfg.enabled);
            cfg.highlightWither = ConfigJson.getBool(obj, "highlightWither", cfg.highlightWither);
            cfg.highlightBlood = ConfigJson.getBool(obj, "highlightBlood", cfg.highlightBlood);
            cfg.showTracer = ConfigJson.getBool(obj, "showTracer", cfg.showTracer);
            instance = cfg;
        } catch (Exception e) {
            instance = new DoorKeysConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("highlightWither", highlightWither);
            obj.addProperty("highlightBlood", highlightBlood);
            obj.addProperty("showTracer", showTracer);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public boolean isEnabled() {
        return enabled && com.killer560.hub.util.SkyblockGate.allows();
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isHighlightWither() {
        return highlightWither;
    }

    public void setHighlightWither(boolean highlightWither) {
        this.highlightWither = highlightWither;
    }

    public boolean isHighlightBlood() {
        return highlightBlood;
    }

    public void setHighlightBlood(boolean highlightBlood) {
        this.highlightBlood = highlightBlood;
    }

    public boolean isShowTracer() {
        return showTracer;
    }

    public void setShowTracer(boolean showTracer) {
        this.showTracer = showTracer;
    }
}
