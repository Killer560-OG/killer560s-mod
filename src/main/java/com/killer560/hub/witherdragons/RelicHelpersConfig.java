package com.killer560.hub.witherdragons;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.BuildVariant;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModPaths;
import com.killer560.hub.util.SkyblockGate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted Relic Triggerbot and Relic Look settings - see {@link RelicHelpersFeature}. Cheat build only; both ship OFF. */
public final class RelicHelpersConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-relichelpers.json");

    public static final int MAX_COOLDOWN_MS = 1000;
    public static final int MIN_LOOK_RANGE = 3;
    public static final int MAX_LOOK_RANGE = 20;

    private static RelicHelpersConfig instance;

    private boolean triggerEnabled = false;
    private boolean triggerPickup = true;
    private boolean triggerPlace = true;
    private boolean swapToRelic = true;
    /** NoammAddons' Relic Triggerbot: at most one click every 200 ms. */
    private int cooldownMs = 200;

    private boolean lookEnabled = false;
    private boolean lookAtRelic = true;
    private boolean lookAtCauldron = true;
    private int lookRange = 8;

    private RelicHelpersConfig() {
    }

    public static RelicHelpersConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        RelicHelpersConfig cfg = new RelicHelpersConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject root = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
                cfg.triggerEnabled = ConfigJson.getBool(root, "triggerEnabled", false);
                cfg.triggerPickup = ConfigJson.getBool(root, "triggerPickup", true);
                cfg.triggerPlace = ConfigJson.getBool(root, "triggerPlace", true);
                cfg.swapToRelic = ConfigJson.getBool(root, "swapToRelic", true);
                cfg.cooldownMs = ConfigJson.getInt(root, "cooldownMs", 200);
                cfg.lookEnabled = ConfigJson.getBool(root, "lookEnabled", false);
                cfg.lookAtRelic = ConfigJson.getBool(root, "lookAtRelic", true);
                cfg.lookAtCauldron = ConfigJson.getBool(root, "lookAtCauldron", true);
                cfg.lookRange = ConfigJson.getInt(root, "lookRange", 8);
            } catch (Exception ignored) {
                // Unreadable file: the per-key readers keep whatever parsed, the rest stay at defaults.
            }
        }
        instance = cfg;
        com.killer560.hub.util.FeatureBits.changed();
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("triggerEnabled", triggerEnabled);
            root.addProperty("triggerPickup", triggerPickup);
            root.addProperty("triggerPlace", triggerPlace);
            root.addProperty("swapToRelic", swapToRelic);
            root.addProperty("cooldownMs", cooldownMs);
            root.addProperty("lookEnabled", lookEnabled);
            root.addProperty("lookAtRelic", lookAtRelic);
            root.addProperty("lookAtCauldron", lookAtCauldron);
            root.addProperty("lookRange", lookRange);
            Files.writeString(CONFIG_PATH, GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    /** The gate the feature checks - false on the legit jar and outside Skyblock, always. */
    public boolean isTriggerEnabled() {
        return triggerEnabled && BuildVariant.CHEAT_FEATURES_ENABLED && SkyblockGate.allows();
    }

    public boolean isTriggerEnabledRaw() {
        return triggerEnabled;
    }

    public void setTriggerEnabled(boolean v) {
        triggerEnabled = v;
    }

    public boolean isTriggerPickup() {
        return triggerPickup;
    }

    public void setTriggerPickup(boolean v) {
        triggerPickup = v;
    }

    public boolean isTriggerPlace() {
        return triggerPlace;
    }

    public void setTriggerPlace(boolean v) {
        triggerPlace = v;
    }

    public boolean isSwapToRelic() {
        return swapToRelic;
    }

    public void setSwapToRelic(boolean v) {
        swapToRelic = v;
    }

    public int getCooldownMs() {
        return Math.min(MAX_COOLDOWN_MS, Math.max(0, cooldownMs));
    }

    public void setCooldownMs(int v) {
        cooldownMs = v;
    }

    public boolean isLookEnabled() {
        return lookEnabled && BuildVariant.CHEAT_FEATURES_ENABLED && SkyblockGate.allows();
    }

    public boolean isLookEnabledRaw() {
        return lookEnabled;
    }

    public void setLookEnabled(boolean v) {
        lookEnabled = v;
        com.killer560.hub.util.FeatureBits.changed();
    }

    public boolean isLookAtRelic() {
        return lookAtRelic;
    }

    public void setLookAtRelic(boolean v) {
        lookAtRelic = v;
    }

    public boolean isLookAtCauldron() {
        return lookAtCauldron;
    }

    public void setLookAtCauldron(boolean v) {
        lookAtCauldron = v;
    }

    public int getLookRange() {
        return Math.min(MAX_LOOK_RANGE, Math.max(MIN_LOOK_RANGE, lookRange));
    }

    public void setLookRange(int v) {
        lookRange = v;
    }
}
