package com.killer560.hub.carrytracker;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.killer560.hub.util.BoolFields;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModPaths;
import com.killer560.hub.util.SkyblockGate;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Carry Tracker settings, {@code killer560smod-carrytracker.json}: the switch (ships OFF) and the names being carried. The run counts are records (shared store). */
public final class CarryTrackerConfig {

    public static final int MAX_TRACKED = 12;

    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-carrytracker.json");
    private static CarryTrackerConfig instance;

    private boolean enabled = false;
    private final List<String> tracked = new ArrayList<>();

    private static final BoolFields<CarryTrackerConfig> BOOLS = new BoolFields<CarryTrackerConfig>()
            .add("enabled", false, c -> c.enabled, (c, v) -> c.enabled = v);

    private CarryTrackerConfig() {
    }

    public static CarryTrackerConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        CarryTrackerConfig cfg = new CarryTrackerConfig();
        JsonObject obj = ConfigJson.read(CONFIG_PATH);
        if (obj != null) {
            BOOLS.load(cfg, obj);
            if (obj.get("tracked") instanceof JsonArray arr) {
                arr.forEach(e -> cfg.add(e.getAsString()));
            }
        }
        instance = cfg;
    }

    public void save() {
        JsonObject obj = new JsonObject();
        BOOLS.save(this, obj);
        JsonArray arr = new JsonArray();
        tracked.forEach(arr::add);
        obj.add("tracked", arr);
        ConfigJson.write(CONFIG_PATH, obj);
    }

    public boolean isEnabled() { return enabled && SkyblockGate.allows(); }

    public boolean getEnabledRaw() { return enabled; }

    public void setEnabled(boolean v) { enabled = v; }

    public synchronized List<String> getTracked() { return List.copyOf(tracked); }

    /** Adds a Minecraft name (1-16 letters, digits or underscores); false when invalid, already there or the list is full. */
    public synchronized boolean add(String name) {
        String n = name == null ? "" : name.trim();
        if (!n.matches("[A-Za-z0-9_]{1,16}") || tracked.size() >= MAX_TRACKED || isTracked(n)) {
            return false;
        }
        tracked.add(n);
        return true;
    }

    public synchronized void remove(String name) { tracked.removeIf(t -> t.equalsIgnoreCase(name)); }

    public synchronized boolean isTracked(String name) { return tracked.stream().anyMatch(t -> t.equalsIgnoreCase(name)); }
}
