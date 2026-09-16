package com.killer560.hub.commandkeybinds;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted Command Keybinds settings - see {@link CommandKeybindsFeature}. Ships disabled by default,
 *  every keybind unset (-1). */
public final class CommandKeybindsConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-commandkeybinds.json");

    private static CommandKeybindsConfig instance;

    private boolean enabled = false;
    private int petsKey = -1;
    private int storageKey = -1;
    private int armorKey = -1;
    private int equipmentKey = -1;
    private int loadoutsKey = -1;
    private int statsKey = -1;
    private int dungeonHubKey = -1;
    private int potionBagKey = -1;

    private CommandKeybindsConfig() {
    }

    public static CommandKeybindsConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new CommandKeybindsConfig();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            CommandKeybindsConfig cfg = new CommandKeybindsConfig();
            cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
            cfg.petsKey = com.killer560.hub.util.KeyUtil.sanitize(getInt(obj, "petsKey", -1));
            cfg.storageKey = com.killer560.hub.util.KeyUtil.sanitize(getInt(obj, "storageKey", -1));
            cfg.armorKey = com.killer560.hub.util.KeyUtil.sanitize(getInt(obj, "armorKey", -1));
            cfg.equipmentKey = com.killer560.hub.util.KeyUtil.sanitize(getInt(obj, "equipmentKey", -1));
            cfg.loadoutsKey = com.killer560.hub.util.KeyUtil.sanitize(getInt(obj, "loadoutsKey", -1));
            cfg.statsKey = com.killer560.hub.util.KeyUtil.sanitize(getInt(obj, "statsKey", -1));
            cfg.dungeonHubKey = com.killer560.hub.util.KeyUtil.sanitize(getInt(obj, "dungeonHubKey", -1));
            cfg.potionBagKey = com.killer560.hub.util.KeyUtil.sanitize(getInt(obj, "potionBagKey", -1));
            instance = cfg;
        } catch (Exception e) {
            instance = new CommandKeybindsConfig();
        }
    }

    private static int getInt(JsonObject obj, String key, int def) {
        return ConfigJson.getInt(obj, key, def);
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("petsKey", petsKey);
            obj.addProperty("storageKey", storageKey);
            obj.addProperty("armorKey", armorKey);
            obj.addProperty("equipmentKey", equipmentKey);
            obj.addProperty("loadoutsKey", loadoutsKey);
            obj.addProperty("statsKey", statsKey);
            obj.addProperty("dungeonHubKey", dungeonHubKey);
            obj.addProperty("potionBagKey", potionBagKey);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public boolean isEnabled() {
        return enabled && com.killer560.hub.util.SkyblockGate.allows();
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getPetsKey() {
        return petsKey;
    }

    public void setPetsKey(int petsKey) {
        this.petsKey = petsKey;
    }

    public int getStorageKey() {
        return storageKey;
    }

    public void setStorageKey(int storageKey) {
        this.storageKey = storageKey;
    }

    public int getArmorKey() {
        return armorKey;
    }

    public void setArmorKey(int armorKey) {
        this.armorKey = armorKey;
    }

    public int getEquipmentKey() {
        return equipmentKey;
    }

    public void setEquipmentKey(int equipmentKey) {
        this.equipmentKey = equipmentKey;
    }

    public int getLoadoutsKey() {
        return loadoutsKey;
    }

    public void setLoadoutsKey(int loadoutsKey) {
        this.loadoutsKey = loadoutsKey;
    }

    public int getStatsKey() {
        return statsKey;
    }

    public void setStatsKey(int statsKey) {
        this.statsKey = statsKey;
    }

    public int getDungeonHubKey() {
        return dungeonHubKey;
    }

    public void setDungeonHubKey(int dungeonHubKey) {
        this.dungeonHubKey = dungeonHubKey;
    }

    public int getPotionBagKey() {
        return potionBagKey;
    }

    public void setPotionBagKey(int potionBagKey) {
        this.potionBagKey = potionBagKey;
    }
}
