package com.killer560.hub.storagesearch;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted Storage Item Search settings - see {@link StorageSearchFeature}. Ships disabled with the
 *  keybind unset. Every
 *  field here is saved/loaded so it survives a restart. */
public final class StorageSearchConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-storagesearch.json");

    private static StorageSearchConfig instance;

    private boolean enabled = false;
    private int keyCode = -1;
    private boolean searchLore = false;
    private boolean includeInventory = true;
    private boolean openOnClick = true;

    private StorageSearchConfig() {
    }

    public static StorageSearchConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        StorageSearchConfig cfg = new StorageSearchConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
                if (obj.has("enabled")) {
                    cfg.enabled = obj.get("enabled").getAsBoolean();
                }
                if (obj.has("keyCode")) {
                    cfg.keyCode = com.killer560.hub.util.KeyUtil.sanitize(obj.get("keyCode").getAsInt());
                }
                if (obj.has("searchLore")) {
                    cfg.searchLore = obj.get("searchLore").getAsBoolean();
                }
                if (obj.has("includeInventory")) {
                    cfg.includeInventory = obj.get("includeInventory").getAsBoolean();
                }
                if (obj.has("openOnClick")) {
                    cfg.openOnClick = obj.get("openOnClick").getAsBoolean();
                }
            } catch (Exception ignored) {
                cfg = new StorageSearchConfig();
            }
        }
        instance = cfg;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("keyCode", keyCode);
            obj.addProperty("searchLore", searchLore);
            obj.addProperty("includeInventory", includeInventory);
            obj.addProperty("openOnClick", openOnClick);
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

    public int getKeyCode() {
        return keyCode;
    }

    public void setKeyCode(int keyCode) {
        this.keyCode = keyCode;
    }

    public boolean isSearchLore() {
        return searchLore;
    }

    public void setSearchLore(boolean searchLore) {
        this.searchLore = searchLore;
    }

    public boolean isIncludeInventory() {
        return includeInventory;
    }

    public void setIncludeInventory(boolean includeInventory) {
        this.includeInventory = includeInventory;
    }

    public boolean isOpenOnClick() {
        return openOnClick;
    }

    public void setOpenOnClick(boolean openOnClick) {
        this.openOnClick = openOnClick;
    }
}
