package com.killer560.hub.gui;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Whether the short descriptions show (killer560, 2026-10-15: "Have a toggle to show those descriptions at all for the gui
 * editor as well"): the one-line blurb on every feature card in the mod menu, and the hint lines of the HUD editor.
 * Both are on by default. The long hover descriptions are not affected. The tab's own switch (killer560, 2026-10-20: "make
 * descriptions have a normal toggle for the whole setting as well") is over both: off hides every one-line description
 * and every editor hint, whatever the two rows say, and keeps their values for when it is turned back on. On by default.
 */
public final class DescriptionConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-menu-descriptions.json");

    private static DescriptionConfig instance;

    private boolean enabled = true;
    private boolean showMenu = true;
    private boolean showEditor = true;

    private DescriptionConfig() {
    }

    public static DescriptionConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        DescriptionConfig cfg = new DescriptionConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8))
                        .getAsJsonObject();
                cfg.enabled = ConfigJson.getBool(obj, "enabled", true);
                cfg.showMenu = ConfigJson.getBool(obj, "showMenu", true);
                cfg.showEditor = ConfigJson.getBool(obj, "showEditor", true);
            } catch (Exception ignored) {
                cfg = new DescriptionConfig();
            }
        }
        instance = cfg;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("showMenu", showMenu);
            obj.addProperty("showEditor", showEditor);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    /** The tab's switch over both rows. */
    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean v) {
        enabled = v;
    }

    /** Whether the feature cards in the mod menu show their one-line description now: the switch and the row both on. */
    public boolean menuShown() {
        return enabled && showMenu;
    }

    /** Whether the HUD editor shows its hint lines now: the switch and the row both on. */
    public boolean editorShown() {
        return enabled && showEditor;
    }

    /** The Show Descriptions row's own value (what {@link #menuShown} uses while the switch is on). */
    public boolean isShowMenu() {
        return showMenu;
    }

    public void setShowMenu(boolean v) {
        showMenu = v;
    }

    /** The Show Editor Descriptions row's own value (what {@link #editorShown} uses while the switch is on). */
    public boolean isShowEditor() {
        return showEditor;
    }

    public void setShowEditor(boolean v) {
        showEditor = v;
    }
}
