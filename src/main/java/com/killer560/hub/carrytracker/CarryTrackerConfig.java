package com.killer560.hub.carrytracker;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.killer560.hub.util.BoolFields;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModPaths;
import com.killer560.hub.util.SkyblockGate;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Carry Tracker settings, {@code killer560smod-carrytracker.json}: the switch (ships OFF), Remove When Done, the default runs
 * per client, and the clients being carried, each with its own runs target. The run counts are records (shared store).
 */
public final class CarryTrackerConfig {

    public static final int MAX_TRACKED = 12;
    public static final int MIN_RUNS = 1;
    public static final int MAX_RUNS = 50;

    /** One client: the name and how many runs to carry them. */
    public record Client(String name, int runs) {
    }

    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-carrytracker.json");
    private static CarryTrackerConfig instance;

    private boolean enabled = false;
    private boolean removeWhenDone = false;
    private int defaultRuns = 5;
    private final List<Client> tracked = new ArrayList<>();
    /** Bumped by every change to the list or the switch, so the HUD rebuilds its rows only when something moved. */
    private volatile int rev;

    private static final BoolFields<CarryTrackerConfig> BOOLS = new BoolFields<CarryTrackerConfig>()
            .add("enabled", false, c -> c.enabled, (c, v) -> c.enabled = v)
            .add("removeWhenDone", false, c -> c.removeWhenDone, (c, v) -> c.removeWhenDone = v);

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
            if (obj.get("defaultRuns") != null && obj.get("defaultRuns").isJsonPrimitive()) {
                cfg.defaultRuns = clamp(obj.get("defaultRuns").getAsInt());
            }
            if (obj.get("tracked") instanceof JsonArray arr) {
                for (JsonElement e : arr) {
                    if (e.isJsonObject()) {
                        JsonObject o = e.getAsJsonObject();
                        cfg.add(o.get("name").getAsString(), o.has("runs") ? o.get("runs").getAsInt() : cfg.defaultRuns);
                    } else {
                        cfg.add(e.getAsString(), cfg.defaultRuns);
                    }
                }
            }
        }
        instance = cfg;
    }

    public void save() {
        JsonObject obj = new JsonObject();
        BOOLS.save(this, obj);
        obj.addProperty("defaultRuns", defaultRuns);
        JsonArray arr = new JsonArray();
        for (Client c : getTracked()) {
            JsonObject o = new JsonObject();
            o.addProperty("name", c.name());
            o.addProperty("runs", c.runs());
            arr.add(o);
        }
        obj.add("tracked", arr);
        ConfigJson.write(CONFIG_PATH, obj);
    }

    private static int clamp(int runs) { return Math.max(MIN_RUNS, Math.min(MAX_RUNS, runs)); }

    public boolean isEnabled() { return enabled && SkyblockGate.allows(); }

    public boolean getEnabledRaw() { return enabled; }

    public void setEnabled(boolean v) { enabled = v; rev++; }

    public boolean isRemoveWhenDone() { return removeWhenDone; }

    public void setRemoveWhenDone(boolean v) { removeWhenDone = v; }

    public int getDefaultRuns() { return defaultRuns; }

    public void setDefaultRuns(int v) { defaultRuns = clamp(v); }

    public int rev() { return rev; }

    public synchronized List<Client> getTracked() { return List.copyOf(tracked); }

    /** Adds a client with the default runs target. */
    public boolean add(String name) { return add(name, defaultRuns); }

    /** Adds a Minecraft name (1-16 letters, digits or underscores); false when invalid, already there or the list is full. A new client starts at 0 runs done. */
    public synchronized boolean add(String name, int runs) {
        String n = name == null ? "" : name.trim();
        if (!n.matches("[A-Za-z0-9_]{1,16}") || tracked.size() >= MAX_TRACKED || isTracked(n)) {
            return false;
        }
        tracked.add(new Client(n, clamp(runs)));
        rev++;
        return true;
    }

    public synchronized void remove(String name) {
        tracked.removeIf(t -> t.name().equalsIgnoreCase(name));
        rev++;
    }

    public synchronized boolean isTracked(String name) { return tracked.stream().anyMatch(t -> t.name().equalsIgnoreCase(name)); }

    /** The runs target for {@code name}, or 0 when not tracked. */
    public synchronized int getRuns(String name) {
        return tracked.stream().filter(t -> t.name().equalsIgnoreCase(name)).mapToInt(Client::runs).findFirst().orElse(0);
    }

    public synchronized void setRuns(String name, int runs) {
        tracked.replaceAll(t -> t.name().equalsIgnoreCase(name) ? new Client(t.name(), clamp(runs)) : t);
        rev++;
    }
}
