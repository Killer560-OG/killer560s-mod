package com.killer560.hub.roomsim;

import com.google.gson.JsonObject;
import com.killer560.hub.util.BoolFields;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModPaths;

import java.nio.file.Path;

/**
 * Sim Multiplayer's owner settings: what a guest may ask the host's server for (each default ON) and the last room
 * name and size Create used, and the receiver's side of invites (phase 3): Receive Sim Invites (default ON, the sharing
 * convention) and the inviters he blocked. The password is never saved. Read by the server thread (volatile fields), written by the
 * menu; reloaded with a profile ({@code ProfileManager.reloadAllConfigs}).
 */
public final class SimCoopConfig {

    static final Path CONFIG_PATH = ModPaths.config("killer560smod-sim-coop.json");

    private static final BoolFields<SimCoopConfig> BOOLS = new BoolFields<SimCoopConfig>()
            .add("guestsTeleport", true, c -> c.teleport, (c, v) -> c.teleport = v)
            .add("guestsSpeed", true, c -> c.speed, (c, v) -> c.speed = v)
            .add("guestsFly", true, c -> c.fly, (c, v) -> c.fly = v)
            .add("guestsLoadout", true, c -> c.loadout, (c, v) -> c.loadout = v)
            .add("guestsItems", true, c -> c.items, (c, v) -> c.items = v)
            .add("receiveInvites", true, c -> c.receiveInvites, (c, v) -> c.receiveInvites = v);
    /** Most blocked inviters kept (the relay takes 100 a socket). */
    static final int MAX_BLOCKED = 100;

    private static final SimCoopConfig INSTANCE = new SimCoopConfig();

    volatile boolean teleport = true, speed = true, fly = true, loadout = true, items = true, receiveInvites = true;
    /** Blocked inviters, uuid (as the relay sent it) -> name, oldest first. Replaced whole on change. */
    private volatile java.util.Map<String, String> blocked = java.util.Map.of();
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
        java.util.Map<String, String> b = new java.util.LinkedHashMap<>();
        if (o.has("blockedInviters") && o.get("blockedInviters").isJsonObject()) {
            for (var e : o.getAsJsonObject("blockedInviters").entrySet()) {
                if (b.size() < MAX_BLOCKED && e.getValue().isJsonPrimitive()) b.put(e.getKey(), e.getValue().getAsString());
            }
        }
        c.blocked = java.util.Collections.unmodifiableMap(b);
        SimReach.settingsChanged();
    }

    public static void save() {
        SimCoopConfig c = get();
        JsonObject o = new JsonObject();
        BOOLS.save(c, o);
        o.addProperty("roomName", c.roomName);
        o.addProperty("maxPlayers", c.maxPlayers);
        JsonObject b = new JsonObject();
        c.blocked.forEach(b::addProperty);
        o.add("blockedInviters", b);
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

    public boolean receiveInvites() { return receiveInvites; }

    public void setReceiveInvites(boolean on) {
        receiveInvites = on;
        save();
        SimReach.settingsChanged();
    }

    /** Blocked inviters, uuid -> name. */
    public java.util.Map<String, String> blocked() { return blocked; }

    /** Blocks (true) or unblocks one inviter; the oldest block goes past {@link #MAX_BLOCKED}. */
    public void setBlocked(String uuid, String name, boolean block) {
        java.util.Map<String, String> b = new java.util.LinkedHashMap<>(blocked);
        if (block) {
            b.remove(uuid);
            b.put(uuid, name == null ? "" : name);
            while (b.size() > MAX_BLOCKED) b.remove(b.keySet().iterator().next());
        } else {
            b.remove(uuid);
        }
        blocked = java.util.Collections.unmodifiableMap(b);
        save();
        SimReach.settingsChanged();
    }

    public void clearBlocked() {
        blocked = java.util.Map.of();
        save();
        SimReach.settingsChanged();
    }

    public String roomName() { return roomName; }

    public int maxPlayers() { return maxPlayers; }

    public void setRoom(String name, int max) {
        roomName = name;
        maxPlayers = Math.max(2, Math.min(5, max));
        save();
    }
}
