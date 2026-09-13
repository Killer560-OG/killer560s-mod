package com.killer560.hub.etherwarp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Only the "show the HUD list" toggle is persisted here - the waypoints themselves live entirely in
 *  {@link EtherwarpFeature}'s in-memory list, never written to disk. See that class's doc for why. */
public final class EtherwarpWaypointsConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-etherwarp.json");

    private static EtherwarpWaypointsConfig instance;

    private boolean enabled = false;

    private EtherwarpWaypointsConfig() {
    }

    public static EtherwarpWaypointsConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new EtherwarpWaypointsConfig();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            EtherwarpWaypointsConfig cfg = new EtherwarpWaypointsConfig();
            cfg.enabled = obj.has("enabled") && obj.get("enabled").getAsBoolean();
            instance = cfg;
        } catch (Exception e) {
            instance = new EtherwarpWaypointsConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
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
}
