package com.killer560.hub.secrettrigger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted "Secret Triggerbot" settings - see {@link SecretTriggerbotFeature}. Cheat build only, ships disabled.
 *  Slider ranges follow QUOI's SecretTriggerbot ("Interact delay" 0-5 ticks is exposed here in ms, same as the
 *  Simon Says trigger bot delay; "Swap slot" 1-9; "Swap back" default off). */
public final class SecretTriggerbotConfig {

    public static final int MAX_DELAY_MS = 500;
    public static final int MAX_COOLDOWN_MS = 2000;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-secrettriggerbot.json");

    private static SecretTriggerbotConfig instance;

    private boolean enabled = false;
    private int delayMs = 0;
    private int cooldownMs = 250;
    private boolean swapEnabled = false;
    private int swapSlot = 1;
    private boolean swapBack = false;

    private SecretTriggerbotConfig() {
    }

    public static SecretTriggerbotConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        SecretTriggerbotConfig cfg = new SecretTriggerbotConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
                cfg.enabled = ConfigJson.getBool(obj, "enabled", cfg.enabled);
                cfg.setDelayMs(ConfigJson.getInt(obj, "delayMs", cfg.delayMs));
                cfg.setCooldownMs(ConfigJson.getInt(obj, "cooldownMs", cfg.cooldownMs));
                cfg.swapEnabled = ConfigJson.getBool(obj, "swapEnabled", cfg.swapEnabled);
                cfg.setSwapSlot(ConfigJson.getInt(obj, "swapSlot", cfg.swapSlot));
                cfg.swapBack = ConfigJson.getBool(obj, "swapBack", cfg.swapBack);
            } catch (Exception e) {
                cfg = new SecretTriggerbotConfig();
            }
        }
        instance = cfg;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("delayMs", delayMs);
            obj.addProperty("cooldownMs", cooldownMs);
            obj.addProperty("swapEnabled", swapEnabled);
            obj.addProperty("swapSlot", swapSlot);
            obj.addProperty("swapBack", swapBack);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    /** Hard-gated: the legit jar can never report true, even from a copied cheat-build config. */
    public boolean isEnabled() {
        return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && enabled && com.killer560.hub.util.SkyblockGate.allows();
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getDelayMs() {
        return delayMs;
    }

    public void setDelayMs(int ms) {
        this.delayMs = Math.max(0, Math.min(MAX_DELAY_MS, ms));
    }

    public int getCooldownMs() {
        return cooldownMs;
    }

    public void setCooldownMs(int ms) {
        this.cooldownMs = Math.max(0, Math.min(MAX_COOLDOWN_MS, ms));
    }

    public boolean isSwapEnabled() {
        return swapEnabled;
    }

    public void setSwapEnabled(boolean swapEnabled) {
        this.swapEnabled = swapEnabled;
    }

    /** 1-9 (hotbar slot number as shown to the player). */
    public int getSwapSlot() {
        return swapSlot;
    }

    public void setSwapSlot(int slot) {
        this.swapSlot = Math.max(1, Math.min(9, slot));
    }

    public boolean isSwapBack() {
        return swapBack;
    }

    public void setSwapBack(boolean swapBack) {
        this.swapBack = swapBack;
    }
}
