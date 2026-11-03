package com.killer560.hub.armorhud;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted Armor HUD settings - see {@link ArmorHudFeature}. Position and scale live in {@code HudConfig}. */
public final class ArmorHudConfig {

    public enum Layout {
        VERTICAL("Vertical"), HORIZONTAL("Horizontal");

        public final String label;

        Layout(String label) {
            this.label = label;
        }

        public Layout step(int dir) {
            int n = values().length;
            return values()[((ordinal() + (dir >= 0 ? 1 : -1)) % n + n) % n];
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-armorhud.json");

    private static ArmorHudConfig instance;

    private boolean enabled = true;
    private boolean showEquipment = true;
    private Layout layout = Layout.VERTICAL;
    private boolean showDurability = false;
    private boolean background = true;

    private ArmorHudConfig() {
    }

    public static ArmorHudConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        ArmorHudConfig cfg = new ArmorHudConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject o = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
                cfg.enabled = ConfigJson.getBool(o, "enabled", cfg.enabled);
                cfg.showEquipment = ConfigJson.getBool(o, "showEquipment", cfg.showEquipment);
                cfg.showDurability = ConfigJson.getBool(o, "showDurability", cfg.showDurability);
                cfg.background = ConfigJson.getBool(o, "background", cfg.background);
                if (o.has("layout")) {
                    try {
                        cfg.layout = Layout.valueOf(o.get("layout").getAsString());
                    } catch (RuntimeException ignored) {
                        // unknown value: keep the default
                    }
                }
            } catch (Exception e) {
                ConfigJson.backupBroken(CONFIG_PATH);
                cfg = new ArmorHudConfig();
            }
        }
        instance = cfg;
        com.killer560.hub.util.FeatureBits.changed();
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject o = new JsonObject();
            o.addProperty("enabled", enabled);
            o.addProperty("showEquipment", showEquipment);
            o.addProperty("layout", layout.name());
            o.addProperty("showDurability", showDurability);
            o.addProperty("background", background);
            Files.writeString(CONFIG_PATH, GSON.toJson(o), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // a failed save keeps the in-memory settings
        }
    }

    /** The switch with the Skyblock Only gate applied. */
    public boolean isEnabled() {
        return enabled && com.killer560.hub.util.SkyblockGate.allows();
    }

    public boolean isEnabledRaw() {
        return enabled;
    }

    public void setEnabled(boolean v) {
        enabled = v;
        com.killer560.hub.util.FeatureBits.changed();
    }

    public boolean isShowEquipment() {
        return showEquipment;
    }

    public void setShowEquipment(boolean v) {
        showEquipment = v;
    }

    public Layout getLayout() {
        return layout;
    }

    public void setLayout(Layout v) {
        layout = v == null ? Layout.VERTICAL : v;
    }

    public boolean isVertical() {
        return layout == Layout.VERTICAL;
    }

    public boolean isShowDurability() {
        return showDurability;
    }

    public void setShowDurability(boolean v) {
        showDurability = v;
    }

    public boolean isBackground() {
        return background;
    }

    public void setBackground(boolean v) {
        background = v;
    }
}
