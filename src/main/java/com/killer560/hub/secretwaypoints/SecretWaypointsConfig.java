package com.killer560.hub.secretwaypoints;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted Secret Waypoints settings - see {@link SecretWaypointsFeature}. Ships disabled by
 *  default. */
public final class SecretWaypointsConfig {

    public enum Style { FILL, OUTLINE, FILL_OUTLINE }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-secretwaypoints.json");

    private static SecretWaypointsConfig instance;

    private boolean enabled = false;
    private Style style = Style.FILL_OUTLINE;
    private boolean mimicDetection = true;
    private int chestColor = 0xFFFFD700;
    private int itemColor = 0xFF55FF55;
    private int witherColor = 0xFF222222;
    private int batColor = 0xFFAA00AA;
    private int redstoneKeyColor = 0xFFFF5555;

    private SecretWaypointsConfig() {
    }

    public static SecretWaypointsConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new SecretWaypointsConfig();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            SecretWaypointsConfig cfg = new SecretWaypointsConfig();
            cfg.enabled = obj.has("enabled") && obj.get("enabled").getAsBoolean();
            if (obj.has("style")) {
                try {
                    cfg.style = Style.valueOf(obj.get("style").getAsString());
                } catch (Exception ignored) {
                }
            }
            cfg.mimicDetection = !obj.has("mimicDetection") || obj.get("mimicDetection").getAsBoolean();
            cfg.chestColor = obj.has("chestColor") ? obj.get("chestColor").getAsInt() : cfg.chestColor;
            cfg.itemColor = obj.has("itemColor") ? obj.get("itemColor").getAsInt() : cfg.itemColor;
            cfg.witherColor = obj.has("witherColor") ? obj.get("witherColor").getAsInt() : cfg.witherColor;
            cfg.batColor = obj.has("batColor") ? obj.get("batColor").getAsInt() : cfg.batColor;
            cfg.redstoneKeyColor = obj.has("redstoneKeyColor") ? obj.get("redstoneKeyColor").getAsInt() : cfg.redstoneKeyColor;
            instance = cfg;
        } catch (Exception e) {
            instance = new SecretWaypointsConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("style", style.name());
            obj.addProperty("mimicDetection", mimicDetection);
            obj.addProperty("chestColor", chestColor);
            obj.addProperty("itemColor", itemColor);
            obj.addProperty("witherColor", witherColor);
            obj.addProperty("batColor", batColor);
            obj.addProperty("redstoneKeyColor", redstoneKeyColor);
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

    public Style getStyle() {
        return style;
    }

    public void setStyle(Style style) {
        this.style = style;
    }

    public boolean isMimicDetection() {
        return mimicDetection;
    }

    public void setMimicDetection(boolean mimicDetection) {
        this.mimicDetection = mimicDetection;
    }

    public int getChestColor() {
        return chestColor;
    }

    public int getItemColor() {
        return itemColor;
    }

    public int getWitherColor() {
        return witherColor;
    }

    public int getBatColor() {
        return batColor;
    }

    public int getRedstoneKeyColor() {
        return redstoneKeyColor;
    }
}
