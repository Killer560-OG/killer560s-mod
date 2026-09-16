package com.killer560.hub.terminaltrigger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.BuildVariant;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.SkyblockGate;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted Terminal Triggerbot settings - see {@link TerminalTriggerbotFeature}. Same shape as
 *  {@code SecretTriggerbotConfig} (enabled / delay / cooldown), minus its hotbar-swap options, which have
 *  no meaning here: opening a terminal doesn't use the held item. Cheat build only; ships disabled. */
public final class TerminalTriggerbotConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-terminaltriggerbot.json");

    public static final int MAX_DELAY_MS = 1000;
    public static final int MAX_COOLDOWN_MS = 2000;
    /** Hypixel's own interact reach - past this the server refuses the click anyway. */
    public static final double MAX_RANGE = 4.0;

    private static TerminalTriggerbotConfig instance;

    private boolean enabled = false;
    private int delayMs = 0;
    private int cooldownMs = 250;
    private double range = 4.0;

    private TerminalTriggerbotConfig() {
    }

    public static TerminalTriggerbotConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        TerminalTriggerbotConfig cfg = new TerminalTriggerbotConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
                JsonObject root = JsonParser.parseString(json).getAsJsonObject();
                cfg.enabled = ConfigJson.getBool(root, "enabled", false);
                cfg.delayMs = ConfigJson.getInt(root, "delayMs", 0);
                cfg.cooldownMs = ConfigJson.getInt(root, "cooldownMs", 250);
                cfg.range = ConfigJson.getDouble(root, "range", 4.0);
            } catch (Exception ignored) {
                // Unreadable file: the per-key readers keep whatever parsed, the rest stay at defaults.
            }
        }
        instance = cfg;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("enabled", enabled);
            root.addProperty("delayMs", delayMs);
            root.addProperty("cooldownMs", cooldownMs);
            root.addProperty("range", range);
            Files.writeString(CONFIG_PATH, GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    /** The gate the feature checks - false on the legit jar and outside Skyblock, always. */
    public boolean isEnabled() {
        return enabled && BuildVariant.CHEAT_FEATURES_ENABLED && SkyblockGate.allows();
    }

    /** The raw stored value, for the toggle's own ON/OFF label. */
    public boolean isEnabledRaw() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getDelayMs() {
        return Math.min(MAX_DELAY_MS, Math.max(0, delayMs));
    }

    public void setDelayMs(int delayMs) {
        this.delayMs = delayMs;
    }

    public int getCooldownMs() {
        return Math.min(MAX_COOLDOWN_MS, Math.max(0, cooldownMs));
    }

    public void setCooldownMs(int cooldownMs) {
        this.cooldownMs = cooldownMs;
    }

    public double getRange() {
        return Math.min(MAX_RANGE, Math.max(0.5, range));
    }

    public void setRange(double range) {
        this.range = range;
    }
}
