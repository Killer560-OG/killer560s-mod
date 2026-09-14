package com.killer560.hub.proximityvoice;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
            cfg.enabled = obj.has("enabled") && obj.get("enabled").getAsBoolean();
            cfg.pushToTalk = !obj.has("pushToTalk") || obj.get("pushToTalk").getAsBoolean();
            cfg.pushToTalkKeyCode = obj.has("pushToTalkKeyCode") ? obj.get("pushToTalkKeyCode").getAsInt() : -1;
            cfg.maxRange = obj.has("maxRange") ? obj.get("maxRange").getAsDouble() : 40.0;
            cfg.outputVolume = obj.has("outputVolume") ? obj.get("outputVolume").getAsFloat() : 1.0f;
            cfg.mutedSelf = obj.has("mutedSelf") && obj.get("mutedSelf").getAsBoolean();
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
        return enabled;
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
