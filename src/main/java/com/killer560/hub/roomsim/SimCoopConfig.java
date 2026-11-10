package com.killer560.hub.roomsim;

import com.google.gson.JsonObject;
import com.killer560.hub.util.BoolFields;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModPaths;

import java.nio.file.Path;

/**
 * Sim Multiplayer's owner settings: what a guest may ask the host's server for (each default ON) and the last room
 * name and size Create used. The password is never saved. Read by the server thread (volatile fields), written by the
 * menu; reloaded with a profile ({@code ProfileManager.reloadAllConfigs}).
 */
public final class SimCoopConfig {

    static final Path CONFIG_PATH = ModPaths.config("killer560smod-sim-coop.json");

    private static final BoolFields<SimCoopConfig> BOOLS = new BoolFields<SimCoopConfig>()
            .add("guestsTeleport", true, c -> c.teleport, (c, v) -> c.teleport = v)
            .add("guestsSpeed", true, c -> c.speed, (c, v) -> c.speed = v)
            .add("guestsFly", true, c -> c.fly, (c, v) -> c.fly = v)
            .add("guestsLoadout", true, c -> c.loadout, (c, v) -> c.loadout = v)
            .add("guestsItems", true, c -> c.items, (c, v) -> c.items = v);

    private static final SimCoopConfig INSTANCE = new SimCoopConfig();

    volatile boolean teleport = true, speed = true, fly = true, loadout = true, items = true;
    volatile String roomName = "";
    volatile int maxPlayers = 5;

    private SimCoopConfig() {
    }

    private static volatile boolean loaded;

    /** Read on first use, not at boot. */
    public static SimCoopConfig get() {
        if (!loaded) load();
        return INSTANCE;
    }

    /** Defaults first, then the file (a profile without the file leaves the defaults). */
    public static void load() {
        SimCoopConfig c = INSTANCE;
        loaded = true;
        JsonObject o = ConfigJson.read(CONFIG_PATH);
        if (o == null) o = new JsonObject();
        BOOLS.load(c, o);
        c.roomName = ConfigJson.getString(o, "roomName", "");
        c.maxPlayers = Math.max(2, Math.min(5, ConfigJson.getInt(o, "maxPlayers", 5)));
    }

    public static void save() {
        SimCoopConfig c = get();
        JsonObject o = new JsonObject();
        BOOLS.save(c, o);
        o.addProperty("roomName", c.roomName);
        o.addProperty("maxPlayers", c.maxPlayers);
        ConfigJson.write(CONFIG_PATH, o);
    }

    /** Whether the owner lets guests ask for {@code kind}. */
    public boolean allows(SimRequests.Kind kind) {
        return switch (kind) {
            case TELEPORT -> teleport;
            case SPEED -> speed;
            case FLY -> fly;
            case LOADOUT -> loadout;
            case ITEM -> items;
        };
    }

    public void setAllowed(SimRequests.Kind kind, boolean on) {
        switch (kind) {
            case TELEPORT -> teleport = on;
            case SPEED -> speed = on;
            case FLY -> fly = on;
            case LOADOUT -> loadout = on;
            case ITEM -> items = on;
        }
        save();
    }

    public String roomName() { return roomName; }

    public int maxPlayers() { return maxPlayers; }

    public void setRoom(String name, int max) {
        roomName = name;
        maxPlayers = Math.max(2, Math.min(5, max));
        save();
    }
}
