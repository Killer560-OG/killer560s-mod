package com.killer560.hub.leapmessage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted Leap Message settings: a master on/off, plus one on/off per message type - both
 *  types can be on at once, in which case both get sent (Leaping To after a short delay if Cringe
 *  also fired - see {@link LeapMessageFeature}) - plus the custom text for the Leaping To message. */
public final class LeapMessageConfig {

    public static final String DEFAULT_CUSTOM_MESSAGE = "Leaping to {name}";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-leapmessage.json");

    private static LeapMessageConfig instance;

    private boolean enabled = false;
    private boolean cringeEnabled = false;
    private boolean leapingToEnabled = false;
    private String customMessage = DEFAULT_CUSTOM_MESSAGE;

    private LeapMessageConfig() {
    }

    public static LeapMessageConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new LeapMessageConfig();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            LeapMessageConfig cfg = new LeapMessageConfig();
            cfg.enabled = obj.has("enabled") && obj.get("enabled").getAsBoolean();
            cfg.cringeEnabled = obj.has("cringeEnabled") && obj.get("cringeEnabled").getAsBoolean();
            cfg.leapingToEnabled = obj.has("leapingToEnabled") && obj.get("leapingToEnabled").getAsBoolean();
            cfg.customMessage = obj.has("customMessage") ? obj.get("customMessage").getAsString() : DEFAULT_CUSTOM_MESSAGE;
            instance = cfg;
        } catch (Exception e) {
            instance = new LeapMessageConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("cringeEnabled", cringeEnabled);
            obj.addProperty("leapingToEnabled", leapingToEnabled);
            obj.addProperty("customMessage", customMessage);
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

    public boolean isCringeEnabled() {
        return cringeEnabled;
    }

    public void setCringeEnabled(boolean cringeEnabled) {
        this.cringeEnabled = cringeEnabled;
    }

    public boolean isLeapingToEnabled() {
        return leapingToEnabled;
    }

    public void setLeapingToEnabled(boolean leapingToEnabled) {
        this.leapingToEnabled = leapingToEnabled;
    }

    public String getCustomMessage() {
        return customMessage;
    }

    public void setCustomMessage(String customMessage) {
        this.customMessage = customMessage;
    }
}
