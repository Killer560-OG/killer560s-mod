package com.killer560.hub.mapping;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted settings for the dungeon-map overlay ideas from killer560's original request ("funny map",
 *  extra info, mimic-room show/hide, player-head class recolor - all things SkyblockAddons-style mods do
 *  by drawing on top of Hypixel's own real vanilla map item, not a custom-rendered minimap).
 *  <p>
 *  <b>Honesty note:</b> none of those 4 toggles below actually change anything yet. Drawing the right
 *  thing on top of the map requires knowing exactly which pixel colors/positions on Hypixel's real F7/M7
 *  map mean "this room is the mimic room" or "this icon is a player of class X" - that's real per-pixel
 *  reverse-engineering data this mod doesn't have confirmed, and guessing at pixel color meanings would
 *  risk drawing flatly wrong information over your real map, which is worse than not drawing anything.
 *  What IS real and working here is {@link MappingFeature#dumpHeldMap()} - it saves the exact raw pixel
 *  data of whatever map you're holding to a file, so you can dump the same room in different real states
 *  (mimic present vs. not, before/after a class icon appears) and diff the files to find the actual
 *  pixel encoding. Once that's known, the 4 toggles below can be wired to something real. */
public final class MappingConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-mapping.json");

    private static MappingConfig instance;

    private boolean enabled = false;
    /** Not yet implemented - see class doc. Persisted so the choice survives once it is. */
    private boolean funnyMapEnabled = false;
    private boolean extraInfoEnabled = false;
    private boolean mimicRoomHighlightEnabled = false;
    private boolean classRecolorEnabled = false;

    private MappingConfig() {
    }

    public static MappingConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new MappingConfig();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            MappingConfig cfg = new MappingConfig();
            cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
            cfg.funnyMapEnabled = ConfigJson.getBool(obj, "funnyMapEnabled", false);
            cfg.extraInfoEnabled = ConfigJson.getBool(obj, "extraInfoEnabled", false);
            cfg.mimicRoomHighlightEnabled = ConfigJson.getBool(obj, "mimicRoomHighlightEnabled", false);
            cfg.classRecolorEnabled = ConfigJson.getBool(obj, "classRecolorEnabled", false);
            instance = cfg;
        } catch (Exception e) {
            instance = new MappingConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("funnyMapEnabled", funnyMapEnabled);
            obj.addProperty("extraInfoEnabled", extraInfoEnabled);
            obj.addProperty("mimicRoomHighlightEnabled", mimicRoomHighlightEnabled);
            obj.addProperty("classRecolorEnabled", classRecolorEnabled);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    /** Master toggle - gates the periodic diagnostic log in {@link MappingFeature}. The "Dump Held Map
     *  Now" button/command works regardless of this, since it's an explicit one-shot user action. */
    public boolean isEnabled() {
        return enabled && com.killer560.hub.util.SkyblockGate.allows();
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isFunnyMapEnabled() {
        return funnyMapEnabled;
    }

    public void setFunnyMapEnabled(boolean funnyMapEnabled) {
        this.funnyMapEnabled = funnyMapEnabled;
    }

    public boolean isExtraInfoEnabled() {
        return extraInfoEnabled;
    }

    public void setExtraInfoEnabled(boolean extraInfoEnabled) {
        this.extraInfoEnabled = extraInfoEnabled;
    }

    public boolean isMimicRoomHighlightEnabled() {
        return mimicRoomHighlightEnabled;
    }

    public void setMimicRoomHighlightEnabled(boolean mimicRoomHighlightEnabled) {
        this.mimicRoomHighlightEnabled = mimicRoomHighlightEnabled;
    }

    public boolean isClassRecolorEnabled() {
        return classRecolorEnabled;
    }

    public void setClassRecolorEnabled(boolean classRecolorEnabled) {
        this.classRecolorEnabled = classRecolorEnabled;
    }
}
