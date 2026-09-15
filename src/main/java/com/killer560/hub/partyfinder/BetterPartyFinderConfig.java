package com.killer560.hub.partyfinder;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted Better Party Finder settings - see {@link BetterPartyFinderFeature}. Ships disabled by
 *  default. No Auto Kick here - deliberately excluded per killer560's own "keep skipping automation
 *  things" instruction; see the feature's own class doc. */
public final class BetterPartyFinderConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-betterpartyfinder.json");

    private static BetterPartyFinderConfig instance;

    private boolean enabled = false;
    private boolean showKickButton = true;

    private BetterPartyFinderConfig() {
    }

    public static BetterPartyFinderConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new BetterPartyFinderConfig();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            BetterPartyFinderConfig cfg = new BetterPartyFinderConfig();
            cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
            cfg.showKickButton = ConfigJson.getBool(obj, "showKickButton", true);
            instance = cfg;
        } catch (Exception e) {
            instance = new BetterPartyFinderConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("showKickButton", showKickButton);
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

    public boolean isShowKickButton() {
        return showKickButton;
    }

    public void setShowKickButton(boolean showKickButton) {
        this.showKickButton = showKickButton;
    }
}
