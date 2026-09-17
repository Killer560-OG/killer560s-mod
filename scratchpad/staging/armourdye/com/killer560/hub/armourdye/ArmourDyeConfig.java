package com.killer560.hub.armourdye;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.KeyUtil;
import com.killer560.hub.util.SkyblockGate;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Persisted Armour Recolour settings - see {@link ArmourDye} for what the overrides actually do. Ships OFF, like
 * every other new feature in this mod.
 * <p>
 * Read with {@link ConfigJson}'s per-key readers (2026-09-15 persistence audit) and, for the entry list, element by
 * element: one hand-edited bad entry is skipped instead of wiping every colour the user has set. Every mutator's
 * caller calls {@link #save()} so a colour picked mid-run survives a Minecraft restart - the mod's standing "every
 * setting must persist" rule.
 * <p>
 * Registered in {@code ProfileManager.reloadAllConfigs()} so switching settings profiles re-reads this file too.
 */
public final class ArmourDyeConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-armourdye.json");

    private static ArmourDyeConfig instance;

    private boolean enabled = false;

    /** Whether the inventory icon follows the skin as well as the worn armour. Costs one more component override. */
    private boolean skinInventoryIcons = true;

    /** Key pressed while hovering a slot (any inventory screen) to add/select that piece. */
    private int captureKey = KeyUtil.NONE;

    /** Insertion-ordered so the settings list doesn't reshuffle itself between openings. */
    private final Map<String, ArmourDyeEntry> entries = new LinkedHashMap<>();

    private ArmourDyeConfig() {
    }

    public static ArmourDyeConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        ArmourDyeConfig cfg = new ArmourDyeConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
                cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
                cfg.skinInventoryIcons = ConfigJson.getBool(obj, "skinInventoryIcons", true);
                cfg.captureKey = KeyUtil.sanitize(ConfigJson.getInt(obj, "captureKey", KeyUtil.NONE));
                JsonArray arr = ConfigJson.getArray(obj, "entries");
                if (arr != null) {
                    for (JsonElement el : arr) {
                        // Per-element: one malformed entry is skipped instead of dropping every saved look.
                        try {
                            if (!el.isJsonObject()) {
                                continue;
                            }
                            JsonObject e = el.getAsJsonObject();
                            String id = ConfigJson.getString(e, "itemId", null);
                            if (id == null || id.isBlank()) {
                                continue;
                            }
                            id = normaliseId(id);
                            ArmourDyeEntry entry = new ArmourDyeEntry(id, ConfigJson.getString(e, "label", id));
                            entry.enabled = ConfigJson.getBool(e, "enabled", true);
                            entry.colorEnabled = ConfigJson.getBool(e, "colorEnabled", false);
                            entry.color = ConfigJson.getInt(e, "color", 0xFFFFFFFF);
                            entry.skin = ArmourSkin.byName(ConfigJson.getString(e, "skin", ArmourSkin.NONE.name()));
                            entry.skinAsset = ConfigJson.getString(e, "skinAsset", "");
                            entry.iconModel = ConfigJson.getString(e, "iconModel", "");
                            entry.trimMaterial = ConfigJson.getString(e, "trimMaterial", "");
                            entry.trimPattern = ConfigJson.getString(e, "trimPattern", "");
                            cfg.entries.put(id, entry);
                        } catch (Exception ignored) {
                        }
                    }
                }
            } catch (Exception ignored) {
                // Unreadable/not-an-object file: fall back to fresh defaults rather than throwing on startup.
            }
        }
        instance = cfg;
        ArmourDye.invalidate();
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("skinInventoryIcons", skinInventoryIcons);
            obj.addProperty("captureKey", captureKey);
            JsonArray arr = new JsonArray();
            for (ArmourDyeEntry entry : entries.values()) {
                JsonObject e = new JsonObject();
                e.addProperty("itemId", entry.itemId);
                e.addProperty("label", entry.label);
                e.addProperty("enabled", entry.enabled);
                e.addProperty("colorEnabled", entry.colorEnabled);
                e.addProperty("color", entry.color);
                e.addProperty("skin", entry.skin.name());
                e.addProperty("skinAsset", entry.skinAsset);
                e.addProperty("iconModel", entry.iconModel);
                e.addProperty("trimMaterial", entry.trimMaterial);
                e.addProperty("trimPattern", entry.trimPattern);
                arr.add(e);
            }
            obj.add("entries", arr);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        } finally {
            // The render path reads a snapshot, so it has to be told even when the write itself failed.
            ArmourDye.invalidate();
        }
    }

    static String normaliseId(String id) {
        return id.trim().toUpperCase(Locale.ROOT);
    }

    // --- master ---

    /** Master toggle, gated by "Skyblock Only" like every other feature's own enabled getter. */
    public boolean isEnabled() {
        return enabled && SkyblockGate.allows();
    }

    /** The raw saved value, ignoring the Skyblock gate - for the settings tab's own display. */
    public boolean isEnabledRaw() {
        return enabled;
    }

    public void setEnabled(boolean value) {
        this.enabled = value;
    }

    public boolean isSkinInventoryIcons() {
        return skinInventoryIcons;
    }

    public void setSkinInventoryIcons(boolean value) {
        this.skinInventoryIcons = value;
    }

    public int getCaptureKey() {
        return captureKey;
    }

    public void setCaptureKey(int key) {
        this.captureKey = KeyUtil.sanitize(key);
    }

    // --- entries ---

    /** Live view, insertion-ordered. Mutate through the methods below so {@link ArmourDye} gets invalidated. */
    public List<ArmourDyeEntry> getEntries() {
        return new ArrayList<>(entries.values());
    }

    public int entryCount() {
        return entries.size();
    }

    public ArmourDyeEntry get(String itemId) {
        return itemId == null ? null : entries.get(normaliseId(itemId));
    }

    /** @return the existing entry for this id, or a freshly added one. Never null for a non-blank id. */
    public ArmourDyeEntry getOrCreate(String itemId, String label) {
        if (itemId == null || itemId.isBlank()) {
            return null;
        }
        String key = normaliseId(itemId);
        ArmourDyeEntry existing = entries.get(key);
        if (existing != null) {
            return existing;
        }
        ArmourDyeEntry entry = new ArmourDyeEntry(key, label);
        entries.put(key, entry);
        return entry;
    }

    public void remove(String itemId) {
        if (itemId != null) {
            entries.remove(normaliseId(itemId));
        }
    }

    public void clear() {
        entries.clear();
    }
}
