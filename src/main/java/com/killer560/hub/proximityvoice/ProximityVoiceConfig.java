package com.killer560.hub.proximityvoice;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted Proximity Voice settings - see {@link ProximityVoiceFeature}. Ships disabled by default. */
public final class ProximityVoiceConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-proximityvoice.json");

    private static ProximityVoiceConfig instance;

    private boolean enabled = false;
    private boolean pushToTalk = true;
    private int pushToTalkKeyCode = -1;
    private double maxRange = 40.0;
    private float outputVolume = 1.0f;
    private boolean mutedSelf = false;

    private ProximityVoiceConfig() {
    }

    public static ProximityVoiceConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new ProximityVoiceConfig();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            ProximityVoiceConfig cfg = new ProximityVoiceConfig();
            cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
            cfg.pushToTalk = ConfigJson.getBool(obj, "pushToTalk", true);
            cfg.pushToTalkKeyCode = ConfigJson.getInt(obj, "pushToTalkKeyCode", -1);
            // Routed through the real setters (not a direct field assignment) so a hand-edited or
            // corrupted value (e.g. maxRange 0 or negative) gets clamped back into a valid range on load
            // instead of silently making proximity voice permanently inaudible with no visible error -
            // real bug found and fixed 2026-09-14, pre-testing bug-review pass.
            cfg.setMaxRange(ConfigJson.getDouble(obj, "maxRange", 40.0));
            cfg.setOutputVolume(ConfigJson.getFloat(obj, "outputVolume", 1.0f));
            cfg.mutedSelf = ConfigJson.getBool(obj, "mutedSelf", false);
            instance = cfg;
        } catch (Exception e) {
            instance = new ProximityVoiceConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("pushToTalk", pushToTalk);
            obj.addProperty("pushToTalkKeyCode", pushToTalkKeyCode);
            obj.addProperty("maxRange", maxRange);
            obj.addProperty("outputVolume", outputVolume);
            obj.addProperty("mutedSelf", mutedSelf);
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

    public boolean isPushToTalk() {
        return pushToTalk;
    }

    public void setPushToTalk(boolean pushToTalk) {
        this.pushToTalk = pushToTalk;
    }

    public int getPushToTalkKeyCode() {
        return pushToTalkKeyCode;
    }

    public void setPushToTalkKeyCode(int pushToTalkKeyCode) {
        this.pushToTalkKeyCode = pushToTalkKeyCode;
    }

    public double getMaxRange() {
        return maxRange;
    }

    public void setMaxRange(double maxRange) {
        this.maxRange = Math.max(8.0, Math.min(128.0, maxRange));
    }

    public float getOutputVolume() {
        return outputVolume;
    }

    public void setOutputVolume(float outputVolume) {
        this.outputVolume = Math.max(0f, Math.min(1f, outputVolume));
    }

    public boolean isMutedSelf() {
        return mutedSelf;
    }

    public void setMutedSelf(boolean mutedSelf) {
        this.mutedSelf = mutedSelf;
    }
}
