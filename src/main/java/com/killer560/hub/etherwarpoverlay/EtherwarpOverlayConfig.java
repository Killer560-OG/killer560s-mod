package com.killer560.hub.etherwarpoverlay;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted Etherwarp Overlay settings - see {@link EtherwarpOverlayFeature}'s class doc for the real
 *  Odin-ported landing-prediction this is built on. Ships disabled by default, same as every other new
 *  feature in this mod. */
public final class EtherwarpOverlayConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-etherwarpoverlay.json");

    private static EtherwarpOverlayConfig instance;

    private boolean enabled = false;
    private boolean showWhenFailed = true;
    private boolean fullBlock = false;

    private EtherwarpOverlayConfig() {
    }

    public static EtherwarpOverlayConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new EtherwarpOverlayConfig();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            EtherwarpOverlayConfig cfg = new EtherwarpOverlayConfig();
            cfg.enabled = obj.has("enabled") && obj.get("enabled").getAsBoolean();
            cfg.showWhenFailed = !obj.has("showWhenFailed") || obj.get("showWhenFailed").getAsBoolean();
            cfg.fullBlock = obj.has("fullBlock") && obj.get("fullBlock").getAsBoolean();
            instance = cfg;
        } catch (Exception e) {
            instance = new EtherwarpOverlayConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("showWhenFailed", showWhenFailed);
            obj.addProperty("fullBlock", fullBlock);
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

    public boolean isShowWhenFailed() {
        return showWhenFailed;
    }

    public void setShowWhenFailed(boolean showWhenFailed) {
        this.showWhenFailed = showWhenFailed;
    }

    public boolean isFullBlock() {
        return fullBlock;
    }

    public void setFullBlock(boolean fullBlock) {
        this.fullBlock = fullBlock;
    }
}
