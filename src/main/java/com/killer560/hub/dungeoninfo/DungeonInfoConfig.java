package com.killer560.hub.dungeoninfo;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted settings for the Secrets/Score/Timing info bundle - see {@link DungeonInfoFeature}.
 *  Everything ships off by default, per killer560's standing instruction for new features. */
public final class DungeonInfoConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-dungeoninfo.json");

    private static DungeonInfoConfig instance;

    private boolean secretsHudEnabled = false;
    private boolean mimicMessageEnabled = false;
    private String mimicKeyword = "mimic";
    private String mimicMessage = "Mimic found!";
    private boolean princeMessageEnabled = false;
    private String princeKeyword = "prince";
    private String princeMessage = "Prince spawned!";
    private boolean batMessageEnabled = false;
    private String batKeyword = "bat";
    private String batMessage = "Party bat found!";

    private boolean score270Enabled = false;
    private String score270Message = "270 score - carrying/leaving is fine from here!";
    private boolean score300Enabled = false;
    private String score300Message = "300 score!";

    private boolean timeTrackerEnabled = false;

    private DungeonInfoConfig() {
    }

    public static DungeonInfoConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new DungeonInfoConfig();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            DungeonInfoConfig cfg = new DungeonInfoConfig();
            cfg.secretsHudEnabled = obj.has("secretsHudEnabled") && obj.get("secretsHudEnabled").getAsBoolean();
            cfg.mimicMessageEnabled = obj.has("mimicMessageEnabled") && obj.get("mimicMessageEnabled").getAsBoolean();
            cfg.mimicKeyword = getString(obj, "mimicKeyword", cfg.mimicKeyword);
            cfg.mimicMessage = getString(obj, "mimicMessage", cfg.mimicMessage);
            cfg.princeMessageEnabled = obj.has("princeMessageEnabled") && obj.get("princeMessageEnabled").getAsBoolean();
            cfg.princeKeyword = getString(obj, "princeKeyword", cfg.princeKeyword);
            cfg.princeMessage = getString(obj, "princeMessage", cfg.princeMessage);
            cfg.batMessageEnabled = obj.has("batMessageEnabled") && obj.get("batMessageEnabled").getAsBoolean();
            cfg.batKeyword = getString(obj, "batKeyword", cfg.batKeyword);
            cfg.batMessage = getString(obj, "batMessage", cfg.batMessage);
            cfg.score270Enabled = obj.has("score270Enabled") && obj.get("score270Enabled").getAsBoolean();
            cfg.score270Message = getString(obj, "score270Message", cfg.score270Message);
            cfg.score300Enabled = obj.has("score300Enabled") && obj.get("score300Enabled").getAsBoolean();
            cfg.score300Message = getString(obj, "score300Message", cfg.score300Message);
            cfg.timeTrackerEnabled = obj.has("timeTrackerEnabled") && obj.get("timeTrackerEnabled").getAsBoolean();
            instance = cfg;
        } catch (Exception e) {
            instance = new DungeonInfoConfig();
        }
    }

    private static String getString(JsonObject obj, String key, String fallback) {
        return obj.has(key) ? obj.get(key).getAsString() : fallback;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("secretsHudEnabled", secretsHudEnabled);
            obj.addProperty("mimicMessageEnabled", mimicMessageEnabled);
            obj.addProperty("mimicKeyword", mimicKeyword);
            obj.addProperty("mimicMessage", mimicMessage);
            obj.addProperty("princeMessageEnabled", princeMessageEnabled);
            obj.addProperty("princeKeyword", princeKeyword);
            obj.addProperty("princeMessage", princeMessage);
            obj.addProperty("batMessageEnabled", batMessageEnabled);
            obj.addProperty("batKeyword", batKeyword);
            obj.addProperty("batMessage", batMessage);
            obj.addProperty("score270Enabled", score270Enabled);
            obj.addProperty("score270Message", score270Message);
            obj.addProperty("score300Enabled", score300Enabled);
            obj.addProperty("score300Message", score300Message);
            obj.addProperty("timeTrackerEnabled", timeTrackerEnabled);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public boolean isSecretsHudEnabled() {
        return secretsHudEnabled;
    }

    public void setSecretsHudEnabled(boolean secretsHudEnabled) {
        this.secretsHudEnabled = secretsHudEnabled;
    }

    public boolean isMimicMessageEnabled() {
        return mimicMessageEnabled;
    }

    public void setMimicMessageEnabled(boolean v) {
        this.mimicMessageEnabled = v;
    }

    public String getMimicKeyword() {
        return mimicKeyword;
    }

    public void setMimicKeyword(String v) {
        this.mimicKeyword = v;
    }

    public String getMimicMessage() {
        return mimicMessage;
    }

    public void setMimicMessage(String v) {
        this.mimicMessage = v;
    }

    public boolean isPrinceMessageEnabled() {
        return princeMessageEnabled;
    }

    public void setPrinceMessageEnabled(boolean v) {
        this.princeMessageEnabled = v;
    }

    public String getPrinceKeyword() {
        return princeKeyword;
    }

    public void setPrinceKeyword(String v) {
        this.princeKeyword = v;
    }

    public String getPrinceMessage() {
        return princeMessage;
    }

    public void setPrinceMessage(String v) {
        this.princeMessage = v;
    }

    public boolean isBatMessageEnabled() {
        return batMessageEnabled;
    }

    public void setBatMessageEnabled(boolean v) {
        this.batMessageEnabled = v;
    }

    public String getBatKeyword() {
        return batKeyword;
    }

    public void setBatKeyword(String v) {
        this.batKeyword = v;
    }

    public String getBatMessage() {
        return batMessage;
    }

    public void setBatMessage(String v) {
        this.batMessage = v;
    }

    public boolean isScore270Enabled() {
        return score270Enabled;
    }

    public void setScore270Enabled(boolean v) {
        this.score270Enabled = v;
    }

    public String getScore270Message() {
        return score270Message;
    }

    public void setScore270Message(String v) {
        this.score270Message = v;
    }

    public boolean isScore300Enabled() {
        return score300Enabled;
    }

    public void setScore300Enabled(boolean v) {
        this.score300Enabled = v;
    }

    public String getScore300Message() {
        return score300Message;
    }

    public void setScore300Message(String v) {
        this.score300Message = v;
    }

    public boolean isTimeTrackerEnabled() {
        return timeTrackerEnabled;
    }

    public void setTimeTrackerEnabled(boolean v) {
        this.timeTrackerEnabled = v;
    }
}
