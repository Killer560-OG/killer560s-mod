package com.killer560.hub.dvd;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

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
                Set<String> seenIds = new HashSet<>();
                for (JsonElement el : array) {
                    // One malformed entry is skipped on its own instead of dropping every DVD after it.
                    try {
                        if (el == null || !el.isJsonObject()) {
                            continue;
                        }
                        JsonObject obj = el.getAsJsonObject();
                        DvdEntry e = new DvdEntry();
                        e.id = getString(obj, "id", e.id);
                        if (e.id.isBlank() || !seenIds.add(e.id)) {
                            // Blank/duplicate id would make Edit/Delete hit the wrong row - give it a fresh one.
                            e.id = UUID.randomUUID().toString();
                            seenIds.add(e.id);
                        }
                        e.name = getString(obj, "name", e.name);
                        // DvdEntry defaults enabled=true; a missing key used to load as false.
                        e.enabled = ConfigJson.getBool(obj, "enabled", e.enabled);
                        e.contentType = ConfigJson.getEnum(obj, "contentType", DvdContentType.class, e.contentType);
                        e.text = getString(obj, "text", e.text);
                        e.gifFileName = getString(obj, "gifFileName", e.gifFileName);
                        e.textColorHex = getString(obj, "textColorHex", e.textColorHex);
                        e.backgroundEnabled = ConfigJson.getBool(obj, "backgroundEnabled", e.backgroundEnabled);
                        // Same floors DvdTab's Set buttons apply.
                        e.speedMultiplier = Math.max(0.05f, ConfigJson.getFloat(obj, "speedMultiplier", e.speedMultiplier));
                        e.boxWidth = Math.max(4, ConfigJson.getInt(obj, "boxWidth", e.boxWidth));
                        e.boxHeight = Math.max(4, ConfigJson.getInt(obj, "boxHeight", e.boxHeight));
                        e.scale = Math.max(0.05f, ConfigJson.getFloat(obj, "scale", e.scale));
                        e.cornerHitSoundFile = getString(obj, "cornerHitSoundFile", e.cornerHitSoundFile);
                        e.cornerHitText = getString(obj, "cornerHitText", e.cornerHitText);
                        e.changeColorOnCornerHit = ConfigJson.getBool(obj, "changeColorOnCornerHit", e.changeColorOnCornerHit);
                        e.changeColorOnWallHit = ConfigJson.getBool(obj, "changeColorOnWallHit", e.changeColorOnWallHit);
                        cfg.entries.add(e);
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception ignored) {
            }
        }
        instance = cfg;
    }

    /** Never returns null (a JSON null would otherwise NPE later in the tab/feature). */
    private static String getString(JsonObject obj, String key, String fallback) {
        String s = ConfigJson.getString(obj, key, fallback);
        return s == null ? fallback : s;
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
