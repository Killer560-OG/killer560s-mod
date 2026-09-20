package com.killer560.hub.namechanger;

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
import java.util.List;

/**
 * Persisted "Name Changer" (nick hider) settings - see {@link NameChangerFeature}. Ships disabled by default.
 * Every setting (including the whole mapping list) is written to {@code killer560smod-namechanger.json} on
 * every change so it survives a restart. Client-side/visual only: nothing here ever changes text sent to the
 * server.
 */
public final class NameChangerConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-namechanger.json");

    private static NameChangerConfig instance;

    /** Bumped on every mutation so {@link NameChangerFeature} knows to rebuild its lookup table. */
    private static volatile int version = 0;

    /** One "realName=displayName" row. {@code display} is the plain text; {@code color} is the picked ARGB
     *  ({@link NameColor#NONE} = leave the surrounding colour alone). {@code display} may still contain
     *  {@code §} format codes (bold, italic) for anyone who wants them. */
    public static final class Mapping {
        public String real;
        public String display;
        public int color;

        public Mapping(String real, String display) {
            this(real, display, NameColor.NONE);
        }

        public Mapping(String real, String display, int color) {
            this.real = real == null ? "" : real;
            this.display = display == null ? "" : display;
            this.color = color;
        }
    }

    private boolean enabled = false;
    private boolean ownNameEnabled = true;
    /** What your own IGN is shown as. Blank = own name is left alone. */
    private String ownDisplayName = "";
    /** Picked colour for {@link #ownDisplayName} - see {@link NameColor}. */
    private int ownColor = NameColor.NONE;
    private boolean mappingsEnabled = true;
    private final List<Mapping> mappings = new ArrayList<>();
    /** Gives every other player (tab list + loaded players) a stable random fake name for this session. */
    private boolean randomizeOthers = false;

    private NameChangerConfig() {
    }

    public static NameChangerConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static int version() {
        return version;
    }

    public static void load() {
        NameChangerConfig cfg = new NameChangerConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
                cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
                cfg.ownNameEnabled = ConfigJson.getBool(obj, "ownNameEnabled", true);
                cfg.ownDisplayName = ConfigJson.getString(obj, "ownDisplayName", "");
                if (obj.has("ownColor")) {
                    cfg.ownColor = ConfigJson.getInt(obj, "ownColor", NameColor.NONE);
                } else {
                    // Pre-colour-picker config (killer560, 2026-09-20: "instead of using color codes I select
                    // a color for it") - lift the typed &/§ colour code out of the name so his current setup
                    // keeps looking the same with the new picker.
                    NameColor.Migrated migrated = NameColor.migrate(cfg.ownDisplayName);
                    cfg.ownDisplayName = migrated.text();
                    cfg.ownColor = migrated.argb();
                }
                cfg.mappingsEnabled = ConfigJson.getBool(obj, "mappingsEnabled", true);
                cfg.randomizeOthers = ConfigJson.getBool(obj, "randomizeOthers", false);
                JsonArray arr = ConfigJson.getArray(obj, "mappings");
                if (arr != null) {
                    for (JsonElement el : arr) {
                        try {
                            if (el == null || !el.isJsonObject()) {
                                continue;
                            }
                            JsonObject m = el.getAsJsonObject();
                            String display = ConfigJson.getString(m, "display", "");
                            int color = ConfigJson.getInt(m, "color", NameColor.NONE);
                            if (!m.has("color")) {
                                NameColor.Migrated migrated = NameColor.migrate(display);
                                display = migrated.text();
                                color = migrated.argb();
                            }
                            cfg.mappings.add(new Mapping(ConfigJson.getString(m, "real", ""), display, color));
                        } catch (Exception ignored) {
                            // Skip just this malformed row.
                        }
                    }
                }
            } catch (Exception e) {
                cfg = new NameChangerConfig();
            }
        }
        instance = cfg;
        version++;
    }

    public void save() {
        version++;
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("ownNameEnabled", ownNameEnabled);
            obj.addProperty("ownDisplayName", ownDisplayName);
            obj.addProperty("ownColor", ownColor);
            obj.addProperty("mappingsEnabled", mappingsEnabled);
            obj.addProperty("randomizeOthers", randomizeOthers);
            JsonArray arr = new JsonArray();
            for (Mapping m : mappings) {
                JsonObject o = new JsonObject();
                o.addProperty("real", m.real);
                o.addProperty("display", m.display);
                o.addProperty("color", m.color);
                arr.add(o);
            }
            obj.add("mappings", arr);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public boolean isEnabled() {
        return enabled && com.killer560.hub.util.SkyblockGate.allows();
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        version++;
    }

    public boolean isOwnNameEnabled() {
        return ownNameEnabled;
    }

    public void setOwnNameEnabled(boolean ownNameEnabled) {
        this.ownNameEnabled = ownNameEnabled;
        version++;
    }

    public String getOwnDisplayName() {
        return ownDisplayName;
    }

    public void setOwnDisplayName(String ownDisplayName) {
        this.ownDisplayName = ownDisplayName == null ? "" : ownDisplayName;
        version++;
    }

    public int getOwnColor() {
        return ownColor;
    }

    public void setOwnColor(int argb) {
        this.ownColor = argb;
        version++;
    }

    public boolean isMappingsEnabled() {
        return mappingsEnabled;
    }

    public void setMappingsEnabled(boolean mappingsEnabled) {
        this.mappingsEnabled = mappingsEnabled;
        version++;
    }

    public boolean isRandomizeOthers() {
        return randomizeOthers;
    }

    public void setRandomizeOthers(boolean randomizeOthers) {
        this.randomizeOthers = randomizeOthers;
        version++;
    }

    /** Live list - call {@link #save()} after editing an entry in place. */
    public List<Mapping> mappings() {
        return mappings;
    }

    public void addMapping() {
        mappings.add(new Mapping("", ""));
        version++;
    }

    public void removeMapping(Mapping m) {
        mappings.remove(m);
        version++;
    }
}
