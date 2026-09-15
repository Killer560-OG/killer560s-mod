package com.killer560.hub.mobesp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted Star Mob Hitbox ESP settings - see {@link MobEspFeature}. Ships disabled by default. */
public final class MobEspConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-mobesp.json");

    private static MobEspConfig instance;

    private boolean enabled = false;
    /** Legit: an outline only while the mob is actually on your screen with a clear line of sight (a
     *  real raycast check, no through-wall information) - the same category of highlight a "glowing
     *  visible mob" QoL mod already does. Cheat: always glows, including through walls - a real
     *  positional-awareness ESP, gated on {@link com.killer560.hub.BuildVariant#CHEAT_FEATURES_ENABLED}
     *  the same way every other real rule-violating feature in this mod already is. */
    private boolean cheatMode = false;
    private String nameFilter = "✯";
    private String colorHex = "FFD700";
    private double range = 40.0;

    private MobEspConfig() {
    }

    public static MobEspConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new MobEspConfig();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            MobEspConfig cfg = new MobEspConfig();
            cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
            cfg.cheatMode = ConfigJson.getBool(obj, "cheatMode", false);
            cfg.nameFilter = ConfigJson.getString(obj, "nameFilter", "✯");
            cfg.colorHex = ConfigJson.getString(obj, "colorHex", "FFD700");
            // Through the clamping setter so a hand-edited out-of-range value gets the same 5..128 clamp.
            cfg.setRange(ConfigJson.getDouble(obj, "range", 40.0));
            instance = cfg;
        } catch (Exception e) {
            instance = new MobEspConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("cheatMode", cheatMode);
            obj.addProperty("nameFilter", nameFilter);
            obj.addProperty("colorHex", colorHex);
            obj.addProperty("range", range);
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

    /** Gated the same single-source-of-truth way as {@code ExperimentsConfig#isAutonomousMode} - the
     *  legit jar can never report true here even from a copied cheat-build config.json. */
    public boolean isCheatMode() {
        return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && cheatMode;
    }

    public void setCheatMode(boolean cheatMode) {
        this.cheatMode = cheatMode;
    }

    public String getNameFilter() {
        return nameFilter;
    }

    public void setNameFilter(String nameFilter) {
        this.nameFilter = nameFilter;
    }

    public String getColorHex() {
        return colorHex;
    }

    public void setColorHex(String colorHex) {
        this.colorHex = colorHex;
    }

    public double getRange() {
        return range;
    }

    public void setRange(double range) {
        this.range = Math.max(5.0, Math.min(128.0, range));
    }
}
