package com.killer560.hub.dvd;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Persisted list of {@link DvdEntry} - several bouncing DVD boxes can be configured and run at
 *  the same time, each independently. */
public final class DvdConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-dvd.json");

    private static DvdConfig instance;

    private final List<DvdEntry> entries = new ArrayList<>();

    private DvdConfig() {
    }

    public static DvdConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        DvdConfig cfg = new DvdConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
                JsonArray array = JsonParser.parseString(json).getAsJsonArray();
                for (var el : array) {
                    JsonObject obj = el.getAsJsonObject();
                    DvdEntry e = new DvdEntry();
                    e.id = getString(obj, "id", e.id);
                    e.name = getString(obj, "name", e.name);
                    e.enabled = obj.has("enabled") && obj.get("enabled").getAsBoolean();
                    e.contentType = "GIF".equals(getString(obj, "contentType", "TEXT"))
                            ? DvdContentType.GIF : DvdContentType.TEXT;
                    e.text = getString(obj, "text", e.text);
                    e.gifFileName = getString(obj, "gifFileName", e.gifFileName);
                    e.textColorHex = getString(obj, "textColorHex", e.textColorHex);
                    e.backgroundEnabled = obj.has("backgroundEnabled") && obj.get("backgroundEnabled").getAsBoolean();
                    e.speedMultiplier = obj.has("speedMultiplier") ? obj.get("speedMultiplier").getAsFloat() : 1.0f;
                    e.boxWidth = obj.has("boxWidth") ? obj.get("boxWidth").getAsInt() : e.boxWidth;
                    e.boxHeight = obj.has("boxHeight") ? obj.get("boxHeight").getAsInt() : e.boxHeight;
                    e.scale = obj.has("scale") ? obj.get("scale").getAsFloat() : 1.0f;
                    e.cornerHitSoundFile = getString(obj, "cornerHitSoundFile", e.cornerHitSoundFile);
                    e.cornerHitText = getString(obj, "cornerHitText", e.cornerHitText);
                    e.changeColorOnCornerHit = obj.has("changeColorOnCornerHit") && obj.get("changeColorOnCornerHit").getAsBoolean();
                    e.changeColorOnWallHit = obj.has("changeColorOnWallHit") && obj.get("changeColorOnWallHit").getAsBoolean();
                    cfg.entries.add(e);
                }
            } catch (Exception ignored) {
            }
        }
        instance = cfg;
    }

    private static String getString(JsonObject obj, String key, String fallback) {
        return obj.has(key) ? obj.get(key).getAsString() : fallback;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonArray array = new JsonArray();
            for (DvdEntry e : entries) {
                JsonObject obj = new JsonObject();
                obj.addProperty("id", e.id);
                obj.addProperty("name", e.name);
                obj.addProperty("enabled", e.enabled);
                obj.addProperty("contentType", e.contentType.name());
                obj.addProperty("text", e.text);
                obj.addProperty("gifFileName", e.gifFileName);
                obj.addProperty("textColorHex", e.textColorHex);
                obj.addProperty("backgroundEnabled", e.backgroundEnabled);
                obj.addProperty("speedMultiplier", e.speedMultiplier);
                obj.addProperty("boxWidth", e.boxWidth);
                obj.addProperty("boxHeight", e.boxHeight);
                obj.addProperty("scale", e.scale);
                obj.addProperty("cornerHitSoundFile", e.cornerHitSoundFile);
                obj.addProperty("cornerHitText", e.cornerHitText);
                obj.addProperty("changeColorOnCornerHit", e.changeColorOnCornerHit);
                obj.addProperty("changeColorOnWallHit", e.changeColorOnWallHit);
                array.add(obj);
            }
            Files.writeString(CONFIG_PATH, GSON.toJson(array), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public List<DvdEntry> entries() {
        return entries;
    }

    public DvdEntry addNew() {
        DvdEntry e = new DvdEntry();
        e.name = "DVD " + (entries.size() + 1);
        entries.add(e);
        save();
        return e;
    }

    public void remove(String id) {
        entries.removeIf(e -> e.id.equals(id));
        save();
    }
}
