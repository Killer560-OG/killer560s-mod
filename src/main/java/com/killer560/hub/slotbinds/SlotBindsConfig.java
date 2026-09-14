package com.killer560.hub.slotbinds;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Persisted Slot Binds settings - see {@link SlotBindsFeature}'s class doc for the real Odin-ported
 *  swap mechanic this is built on. Ships disabled by default, same as every other new feature in this
 *  mod. */
public final class SlotBindsConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-slotbinds.json");

    private static SlotBindsConfig instance;

    private boolean enabled = false;
    private int bindKey = -1;
    private final Map<Integer, Integer> binds = new LinkedHashMap<>();

    private SlotBindsConfig() {
    }

    public static SlotBindsConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new SlotBindsConfig();
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            SlotBindsConfig cfg = new SlotBindsConfig();
            cfg.enabled = obj.has("enabled") && obj.get("enabled").getAsBoolean();
            cfg.bindKey = obj.has("bindKey") ? obj.get("bindKey").getAsInt() : -1;
            if (obj.has("binds")) {
                JsonObject binds = obj.getAsJsonObject("binds");
                for (String key : binds.keySet()) {
                    try {
                        cfg.binds.put(Integer.parseInt(key), binds.get(key).getAsInt());
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            instance = cfg;
        } catch (Exception e) {
            instance = new SlotBindsConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("bindKey", bindKey);
            JsonObject bindsObj = new JsonObject();
            for (Map.Entry<Integer, Integer> entry : binds.entrySet()) {
                bindsObj.addProperty(String.valueOf(entry.getKey()), entry.getValue());
            }
            obj.add("binds", bindsObj);
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

    public int getBindKey() {
        return bindKey;
    }

    public void setBindKey(int bindKey) {
        this.bindKey = bindKey;
    }

    public Map<Integer, Integer> getBinds() {
        return binds;
    }

    /** Real semantics ported from Odin: a bind links two slots symmetrically for swap purposes - either
     *  end can be shift-clicked to swap with the other, matching {@link SlotBindsFeature}'s real lookup. */
    public void addBind(int slotA, int slotB) {
        binds.put(slotA, slotB);
        binds.put(slotB, slotA);
    }

    public void removeBind(int slot) {
        Integer other = binds.remove(slot);
        if (other != null) {
            binds.remove(other);
        }
    }
}
