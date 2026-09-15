package com.killer560.hub.spiritleap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted Custom Leap Menu settings - see {@link SpiritLeapOverlayFeature}'s class doc for the real
 *  Odin-ported overlay this is built on. Ships disabled by default, same as every other new feature in
 *  this mod. */
public final class SpiritLeapOverlayConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-spiritleapoverlay.json");

    private static SpiritLeapOverlayConfig instance;

    private boolean enabled = false;
    private boolean useClassColors = true;
    private float scale = 1.0f;

    private SpiritLeapOverlayConfig() {
    }

    public static SpiritLeapOverlayConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new SpiritLeapOverlayConfig();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            SpiritLeapOverlayConfig cfg = new SpiritLeapOverlayConfig();
            cfg.enabled = obj.has("enabled") && obj.get("enabled").getAsBoolean();
            cfg.useClassColors = !obj.has("useClassColors") || obj.get("useClassColors").getAsBoolean();
            cfg.scale = obj.has("scale") ? obj.get("scale").getAsFloat() : 1.0f;
            instance = cfg;
        } catch (Exception e) {
            instance = new SpiritLeapOverlayConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("useClassColors", useClassColors);
            obj.addProperty("scale", scale);
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

    public boolean isUseClassColors() {
        return useClassColors;
    }

    public void setUseClassColors(boolean useClassColors) {
        this.useClassColors = useClassColors;
    }

    public float getScale() {
        return scale;
    }

    public void setScale(float scale) {
        this.scale = Math.max(0.5f, Math.min(2.0f, scale));
    }
}
