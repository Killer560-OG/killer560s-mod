package com.killer560.hub.social;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Our own Friends List (killer560's 8.7) - a small curated list the player adds/removes by name (stored by
 * UUID, never IGN, same rule as {@link BestFriendsStore}), with an optional note each.
 * <p>
 * Persisted the same way every other {@code killer560smod-*.json} setting file is (same load/save shape as
 * {@code teammates.TeammatesConfig}) - unlike {@link BestFriendsStore}'s accumulated time data, a hand-curated
 * friends list is exactly the kind of thing {@code profiles.ProfileManager} is meant to carry between
 * profiles (see the top of the staging notes for the one line that needs adding to its loader list).
 * <p>
 * {@link #enabled} is the {@code /fl} toggle from the brief: OFF (false) means bare {@code /fl} passes
 * straight through to Hypixel's own friends list, exactly as if this mod didn't exist - the brief's explicit
 * "must never hijack his /fl until he turns it on". ON means bare {@code /fl} opens {@link FriendsListScreen}
 * instead. Either way {@code /flcustom} and {@code /flhypixel} always reach the specific one requested,
 * regardless of this setting - see {@link FriendsListCommands}.
 */
public final class FriendsListConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-friendslist.json");

    /** One curated friend. Mutable so a note edit doesn't need to rebuild the whole list. */
    public static final class Friend {
        public final UUID uuid;
        public String lastKnownName;
        public String note;
        public long addedAtMs;

        Friend(UUID uuid) {
            this.uuid = uuid;
        }
    }

    private static FriendsListConfig instance;

    private boolean enabled = false;
    private final List<Friend> friends = new ArrayList<>();

    private FriendsListConfig() {
    }

    public static FriendsListConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        FriendsListConfig cfg = new FriendsListConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
                cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
                JsonArray array = ConfigJson.getArray(obj, "friends");
                if (array != null) {
                    for (JsonElement element : array) {
                        Friend f = fromJson(element);
                        if (f != null) {
                            cfg.friends.add(f);
                        }
                    }
                }
            } catch (Exception e) {
                // Malformed file - start with the toggle off and an empty list rather than throwing.
            }
        }
        instance = cfg;
    }

    private static Friend fromJson(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return null;
        }
        JsonObject obj = element.getAsJsonObject();
        String uuidStr = ConfigJson.getString(obj, "uuid", null);
        if (uuidStr == null) {
            return null;
        }
        UUID uuid;
        try {
            uuid = UUID.fromString(uuidStr);
        } catch (IllegalArgumentException e) {
            return null;
        }
        Friend f = new Friend(uuid);
        f.lastKnownName = ConfigJson.getString(obj, "lastKnownName", "");
        f.note = ConfigJson.getString(obj, "note", "");
        f.addedAtMs = ConfigJson.getLong(obj, "addedAtMs", 0L);
        return f;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            JsonArray array = new JsonArray();
            for (Friend f : friends) {
                JsonObject entry = new JsonObject();
                entry.addProperty("uuid", f.uuid.toString());
                entry.addProperty("lastKnownName", f.lastKnownName == null ? "" : f.lastKnownName);
                entry.addProperty("note", f.note == null ? "" : f.note);
                entry.addProperty("addedAtMs", f.addedAtMs);
                array.add(entry);
            }
            obj.add("friends", array);
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

    /** Read-only snapshot, oldest-added first. */
    public List<Friend> friends() {
        return List.copyOf(friends);
    }

    public Friend byUuid(UUID uuid) {
        for (Friend f : friends) {
            if (f.uuid.equals(uuid)) {
                return f;
            }
        }
        return null;
    }

    public Friend byName(String name) {
        if (name == null) {
            return null;
        }
        for (Friend f : friends) {
            if (name.equalsIgnoreCase(f.lastKnownName)) {
                return f;
            }
        }
        return null;
    }

    /** Adds {@code uuid}/{@code name} if not already a friend (matched by UUID). @return the new-or-existing
     *  entry, or null if it's already there (existing entry's note is left untouched). */
    public Friend add(UUID uuid, String name, String note) {
        if (uuid == null) {
            return null;
        }
        Friend existing = byUuid(uuid);
        if (existing != null) {
            if (name != null && !name.isBlank()) {
                existing.lastKnownName = name;
            }
            return null;
        }
        Friend f = new Friend(uuid);
        f.lastKnownName = name == null ? "" : name;
        f.note = note == null ? "" : note;
        f.addedAtMs = System.currentTimeMillis();
        friends.add(f);
        return f;
    }

    /** @return true if a friend matching {@code uuid} was removed. */
    public boolean remove(UUID uuid) {
        return friends.removeIf(f -> f.uuid.equals(uuid));
    }

    public void setNote(UUID uuid, String note) {
        Friend f = byUuid(uuid);
        if (f != null) {
            f.note = note == null ? "" : note;
        }
    }

    /** Refreshes each friend's stored name through the shared {@code players.PlayerNames} resolver -
     *  "incase they ever change their name" without ever changing which record they are. Cheap: answers
     *  immediately from {@code PlayerNames}' own cache/tab-list scan and only kicks a background Mojang
     *  refresh when that cache entry is missing or a day stale (see that class's doc). */
    public void refreshOnlineNames() {
        for (Friend f : friends) {
            com.killer560.hub.players.PlayerNames.resolveAsync(f.uuid, live -> {
                if (live != null && !live.isBlank()) {
                    f.lastKnownName = live;
                }
            });
        }
    }
}
