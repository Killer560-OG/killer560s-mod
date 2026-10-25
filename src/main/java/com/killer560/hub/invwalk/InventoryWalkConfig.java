package com.killer560.hub.invwalk;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted Inventory Walk settings - see {@link InventoryWalkFeature}. Off by default, like every new feature. */
public final class InventoryWalkConfig {

    /** Which open screens keep the movement keys live. */
    public enum Screens {
        /** Inventories, chests and every other container (Skyblock menus, terminals). */
        CONTAINERS("Containers"),
        /** Any screen except chat and other text input. */
        ALL("All Screens");

        public final String label;

        Screens(String label) {
            this.label = label;
        }
    }

    public static final int MAX_PAUSE_TICKS = 20;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-invwalk.json");

    private static InventoryWalkConfig instance;

    private boolean enabled = false;
    private Screens screens = Screens.CONTAINERS;
    private boolean jump = true;
    private boolean sprint = true;
    /** Ticks the movement keys are let go after a slot click (0 = never). */
    private int pauseAfterClickTicks = 0;

    private InventoryWalkConfig() {
    }

    public static InventoryWalkConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        InventoryWalkConfig cfg = new InventoryWalkConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
                cfg.enabled = ConfigJson.getBool(obj, "enabled", cfg.enabled);
                cfg.screens = ConfigJson.getEnum(obj, "screens", Screens.class, cfg.screens);
                cfg.jump = ConfigJson.getBool(obj, "jump", cfg.jump);
                cfg.sprint = ConfigJson.getBool(obj, "sprint", cfg.sprint);
                cfg.setPauseAfterClickTicks(ConfigJson.getInt(obj, "pauseAfterClickTicks", cfg.pauseAfterClickTicks));
            } catch (Exception e) {
                cfg = new InventoryWalkConfig();
            }
        }
        instance = cfg;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("screens", screens.name());
            obj.addProperty("jump", jump);
            obj.addProperty("sprint", sprint);
            obj.addProperty("pauseAfterClickTicks", pauseAfterClickTicks);
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

    public Screens getScreens() {
        return screens;
    }

    public void setScreens(Screens screens) {
        this.screens = screens == null ? Screens.CONTAINERS : screens;
    }

    public boolean isJump() {
        return jump;
    }

    public void setJump(boolean jump) {
        this.jump = jump;
    }

    public boolean isSprint() {
        return sprint;
    }

    public void setSprint(boolean sprint) {
        this.sprint = sprint;
    }

    public int getPauseAfterClickTicks() {
        return pauseAfterClickTicks;
    }

    public void setPauseAfterClickTicks(int ticks) {
        this.pauseAfterClickTicks = Math.max(0, Math.min(MAX_PAUSE_TICKS, ticks));
    }
}
