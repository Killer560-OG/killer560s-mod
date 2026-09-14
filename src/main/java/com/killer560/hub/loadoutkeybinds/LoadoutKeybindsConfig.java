package com.killer560.hub.loadoutkeybinds;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted Loadout Keybinds settings - see {@link LoadoutKeybindsFeature}'s class doc for the real
 *  Odin-ported loadout-navigation this is built on. Ships disabled by default, same as every other new
 *  feature in this mod. */
public final class LoadoutKeybindsConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-loadoutkeybinds.json");

    private static LoadoutKeybindsConfig instance;

    private boolean enabled = false;
    private int nextPageKey = InputConstants.KEY_RIGHT;
    private int previousPageKey = InputConstants.KEY_LEFT;
    // Real default keys ported from Odin's own: 1-9, 0, minus, equals - matching a real vanilla keyboard
    // row's natural left-to-right order for slots 1-12.
    private final int[] slotKeys = {
            InputConstants.KEY_1, InputConstants.KEY_2, InputConstants.KEY_3, InputConstants.KEY_4,
            InputConstants.KEY_5, InputConstants.KEY_6, InputConstants.KEY_7, InputConstants.KEY_8,
            InputConstants.KEY_9, InputConstants.KEY_0, InputConstants.KEY_MINUS, InputConstants.KEY_EQUALS
    };

    private LoadoutKeybindsConfig() {
    }

    public static LoadoutKeybindsConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new LoadoutKeybindsConfig();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            LoadoutKeybindsConfig cfg = new LoadoutKeybindsConfig();
            cfg.enabled = obj.has("enabled") && obj.get("enabled").getAsBoolean();
            cfg.nextPageKey = obj.has("nextPageKey") ? obj.get("nextPageKey").getAsInt() : InputConstants.KEY_RIGHT;
            cfg.previousPageKey = obj.has("previousPageKey") ? obj.get("previousPageKey").getAsInt() : InputConstants.KEY_LEFT;
            if (obj.has("slotKeys")) {
                JsonArray arr = obj.getAsJsonArray("slotKeys");
                for (int i = 0; i < cfg.slotKeys.length && i < arr.size(); i++) {
                    cfg.slotKeys[i] = arr.get(i).getAsInt();
                }
            }
            instance = cfg;
        } catch (Exception e) {
            instance = new LoadoutKeybindsConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("nextPageKey", nextPageKey);
            obj.addProperty("previousPageKey", previousPageKey);
            JsonArray arr = new JsonArray();
            for (int key : slotKeys) {
                arr.add(key);
            }
            obj.add("slotKeys", arr);
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

    public int getNextPageKey() {
        return nextPageKey;
    }

    public void setNextPageKey(int nextPageKey) {
        this.nextPageKey = nextPageKey;
    }

    public int getPreviousPageKey() {
        return previousPageKey;
    }

    public void setPreviousPageKey(int previousPageKey) {
        this.previousPageKey = previousPageKey;
    }

    public int getSlotKey(int index) {
        return slotKeys[index];
    }

    public void setSlotKey(int index, int key) {
        slotKeys[index] = key;
    }
}
