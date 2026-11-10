package com.killer560.hub.collectiontracker;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModPaths;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Collection Tracker settings ({@code killer560smod-collectiontracker.json}): the switch, the picked collections (Hypixel
 * collection ids such as {@code WHEAT} or {@code INK_SACK:3}, in the order picked, at most {@link #MAX_SELECTED}) and
 * Show Rate. Off by default.
 */
public final class CollectionTrackerConfig {

    public static final int MAX_SELECTED = 30;
    public static final int RECENT_MIN = 10, RECENT_MAX = 300;
    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-collectiontracker.json");

    private static CollectionTrackerConfig instance;

    private boolean enabled = false;
    private boolean showRate = true;
    private boolean onlyRecent = false;
    private int recentSeconds = 60;
    private final List<String> selected = new ArrayList<>();

    private CollectionTrackerConfig() {
    }

    public static CollectionTrackerConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        CollectionTrackerConfig cfg = new CollectionTrackerConfig();
        JsonObject obj = ConfigJson.read(CONFIG_PATH);
        if (obj != null) {
            cfg.enabled = ConfigJson.getBool(obj, "enabled", cfg.enabled);
            cfg.showRate = ConfigJson.getBool(obj, "showRate", cfg.showRate);
            cfg.onlyRecent = ConfigJson.getBool(obj, "onlyRecent", cfg.onlyRecent);
            cfg.setRecentSeconds(ConfigJson.getInt(obj, "recentSeconds", cfg.recentSeconds));
            JsonArray arr = ConfigJson.getArray(obj, "selected");
            if (arr != null) {
                for (JsonElement e : arr) {
                    if (e.isJsonPrimitive() && cfg.selected.size() < MAX_SELECTED && !cfg.selected.contains(e.getAsString())) {
                        cfg.selected.add(e.getAsString());
                    }
                }
            }
        }
        instance = cfg;
        CollectionTracker.selectionChanged();
    }

    public void save() {
        JsonObject obj = new JsonObject();
        obj.addProperty("enabled", enabled);
        obj.addProperty("showRate", showRate);
        obj.addProperty("onlyRecent", onlyRecent);
        obj.addProperty("recentSeconds", recentSeconds);
        JsonArray arr = new JsonArray();
        selected.forEach(arr::add);
        obj.add("selected", arr);
        ConfigJson.write(CONFIG_PATH, obj);
    }

    public boolean isEnabled() { return enabled && com.killer560.hub.util.SkyblockGate.allows(); }

    /** The switch itself, whatever the Skyblock Only gate says (the menu row and the HUD editor). */
    public boolean isEnabledRaw() { return enabled; }

    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public boolean isShowRate() { return showRate; }

    public void setShowRate(boolean showRate) {
        this.showRate = showRate;
        CollectionTracker.selectionChanged();
    }

    public boolean isOnlyRecent() { return onlyRecent; }

    public void setOnlyRecent(boolean onlyRecent) {
        this.onlyRecent = onlyRecent;
        CollectionTracker.selectionChanged();
    }

    /** Only Recent's window in seconds, {@link #RECENT_MIN} to {@link #RECENT_MAX}. */
    public int getRecentSeconds() { return recentSeconds; }

    public void setRecentSeconds(int seconds) {
        recentSeconds = Math.max(RECENT_MIN, Math.min(RECENT_MAX, seconds));
        CollectionTracker.selectionChanged();
    }

    /** The picked ids in pick order; do not modify. */
    public List<String> getSelected() { return selected; }

    public boolean isSelected(String id) { return selected.contains(id); }

    /** Picks or drops {@code id}; a pick past {@link #MAX_SELECTED} is refused. */
    public void setSelected(String id, boolean on) {
        if (id == null || id.isBlank()) return;
        if (!on) {
            selected.remove(id);
        } else if (!selected.contains(id) && selected.size() < MAX_SELECTED) {
            selected.add(id);
        }
        CollectionTracker.selectionChanged();
    }
}
