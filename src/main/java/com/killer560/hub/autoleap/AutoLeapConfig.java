package com.killer560.hub.autoleap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted Auto Leap Out settings - see {@link AutoLeapFeature}. A real macro (automates the Spirit
 *  Leap item), so gated on {@link com.killer560.hub.BuildVariant#CHEAT_FEATURES_ENABLED} the same way
 *  every other real automation in this mod is. Ships disabled by default. */
public final class AutoLeapConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-autoleap.json");

    private static AutoLeapConfig instance;

    private boolean enabled = false;
    private String targetName = "";
    private boolean leapOnI4Device = true;
    private boolean leapOnStormDeath = false;
    private boolean leapOnMiddle = false;
    private boolean leapOnRelic = false;
    private boolean leapOnPads = false;

    private AutoLeapConfig() {
    }

    public static AutoLeapConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new AutoLeapConfig();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            AutoLeapConfig cfg = new AutoLeapConfig();
            cfg.enabled = com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED
                    && obj.has("enabled") && obj.get("enabled").getAsBoolean();
            cfg.targetName = obj.has("targetName") ? obj.get("targetName").getAsString() : "";
            cfg.leapOnI4Device = !obj.has("leapOnI4Device") || obj.get("leapOnI4Device").getAsBoolean();
            cfg.leapOnStormDeath = obj.has("leapOnStormDeath") && obj.get("leapOnStormDeath").getAsBoolean();
            cfg.leapOnMiddle = obj.has("leapOnMiddle") && obj.get("leapOnMiddle").getAsBoolean();
            cfg.leapOnRelic = obj.has("leapOnRelic") && obj.get("leapOnRelic").getAsBoolean();
            cfg.leapOnPads = obj.has("leapOnPads") && obj.get("leapOnPads").getAsBoolean();
            instance = cfg;
        } catch (Exception e) {
            instance = new AutoLeapConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("targetName", targetName);
            obj.addProperty("leapOnI4Device", leapOnI4Device);
            obj.addProperty("leapOnStormDeath", leapOnStormDeath);
            obj.addProperty("leapOnMiddle", leapOnMiddle);
            obj.addProperty("leapOnRelic", leapOnRelic);
            obj.addProperty("leapOnPads", leapOnPads);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    /** Gated on {@link com.killer560.hub.BuildVariant#CHEAT_FEATURES_ENABLED} - real automation. */
    public boolean isEnabled() {
        return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getTargetName() {
        return targetName;
    }

    public void setTargetName(String targetName) {
        this.targetName = targetName;
    }

    public boolean isLeapOnI4Device() {
        return leapOnI4Device;
    }

    public void setLeapOnI4Device(boolean v) {
        this.leapOnI4Device = v;
    }

    public boolean isLeapOnStormDeath() {
        return leapOnStormDeath;
    }

    public void setLeapOnStormDeath(boolean v) {
        this.leapOnStormDeath = v;
    }

    public boolean isLeapOnMiddle() {
        return leapOnMiddle;
    }

    public void setLeapOnMiddle(boolean v) {
        this.leapOnMiddle = v;
    }

    public boolean isLeapOnRelic() {
        return leapOnRelic;
    }

    public void setLeapOnRelic(boolean v) {
        this.leapOnRelic = v;
    }

    public boolean isLeapOnPads() {
        return leapOnPads;
    }

    public void setLeapOnPads(boolean v) {
        this.leapOnPads = v;
    }
}
