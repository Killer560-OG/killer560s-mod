package com.killer560.hub.social;

import com.killer560.hub.util.ModPaths;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Our own Friends List menu's data (killer560's 8.7, reworked 2026-09-27): "For the friends list it should go
 * based off of my ingame friendslist. If i add someone in the menu or something then it should also add them
 * on the server." This used to be a small hand-curated list, independent of Hypixel's own - that's exactly
 * the divergence he flagged. It is now a MIRROR of the real Hypixel {@code /fl} output, kept in sync by
 * {@link FriendsListSync} (which also sends the real {@code /f add}/{@code /f remove} for anything done from
 * this mod's own menu - see that class's doc for how the read side actually works and its honestly-documented
 * limits).
 * <p>
 * <b>Keyed by NAME, not UUID</b> - a deliberate exception to this package's own established rule
 * ({@link BestFriendsStore} and the old version of this class both store people by UUID, never IGN, "incase
 * they ever change their name"). Hypixel's real {@code /fl} chat output only ever gives a name, never a UUID,
 * so requiring a UUID up front would mean either blocking the mirror on a Mojang lookup per friend (slow, and
 * wrong the moment it fails for an account that no longer resolves) or inventing a second, UUID-keyed shadow
 * list that could disagree with what {@code /fl} just said - exactly the kind of "half-works and silently
 * diverges" this rework exists to avoid. A UUID is still resolved lazily per friend (for the head icon and a
 * same-tab-list-instance presence check) and cached here, but it never gates whether someone IS a friend.
 * <p>
 * {@link #enabled} is the {@code /fl} toggle from the original brief: OFF (false) means bare {@code /fl}
 * passes straight through to Hypixel's own friends list, exactly as if this mod didn't exist. ON means bare
 * {@code /fl} opens {@link FriendsListScreen} instead. Either way {@code /flcustom} and {@code /flhypixel}
 * always reach the specific one requested, regardless of this setting - see {@link FriendsListCommands}.
 */
public final class FriendsListConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            ModPaths.config("killer560smod-friendslist.json");

    /** One real Hypixel friend, as last mirrored from {@code /fl}. Mutable so a re-sync/note edit doesn't need
     *  to rebuild the whole list. */
    public static final class Friend {
        /** Hypixel's own spelling of the name, as last seen in {@code /fl} - the real identity key (see the
         *  class doc for why this isn't a UUID). */
        public String name;
        /** Resolved lazily through {@code players.PlayerNames} once this friend is first seen this session -
         *  null until then, never required for {@code name} to count as a real friend. Not persisted: a
         *  restart re-resolves it rather than trusting a UUID cache that might be stale for a rename. */
        public transient UUID uuid;
        /** true/false when {@code /fl} categorized this friend as Online/Offline that sync, null when the
         *  real output didn't split by online status that time (see {@link FriendsListSync}'s doc) - in which
         *  case {@link FriendsListScreen} falls back to the old tab-list-only "Nearby now" heuristic. Not
         *  persisted - it's live sync state, stale the moment a restart happens. */
        public transient Boolean onlineHint;
        /** What the last real {@code /fl} said this friend was doing ("SkyBlock - Garden", "Offline", ...),
         *  null if never seen. Persisted with {@link #activityAtMs}, so after a restart it still shows, aged. */
        public String activity;
        public long activityAtMs;
        /** Local-only annotation, never sent to Hypixel and never affects membership. */
        public String note = "";
        public long firstSeenAtMs;

        Friend(String name) {
            this.name = name;
        }
    }

    private static FriendsListConfig instance;

    private boolean enabled = false;
    /** Keyed by lowercase name; iteration order is insertion order, which {@link #applyRealSync} rebuilds
     *  fresh from Hypixel's own listing order every successful sync. */
    private final Map<String, Friend> friends = new LinkedHashMap<>();
    /** Whether {@code /fl} has ever been successfully parsed this - well, ever (persisted, so a restart
     *  doesn't forget and claim "never synced" about a list it showed just fine last session). See
     *  {@link FriendsListScreen}'s "never synced" honesty message, which this gates. */
    private boolean everSynced = false;
    private long lastSyncedAtMs = 0L;
    /** {@link FriendsListSync} sets this when the {@code /fl} block it just parsed looked cut short (an
     *  "and N more" / "..." tail) - Hypixel's own real pagination shape for a long friends list is not
     *  something this mod has been able to confirm against a live session (see that class's doc), so rather
     *  than silently showing a partial list as if it were complete, this is surfaced in the screen. */
    private boolean lastSyncTruncated = false;

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
                cfg.everSynced = ConfigJson.getBool(obj, "everSynced", false);
                cfg.lastSyncedAtMs = ConfigJson.getLong(obj, "lastSyncedAtMs", 0L);
                cfg.lastSyncTruncated = ConfigJson.getBool(obj, "lastSyncTruncated", false);
                JsonArray array = ConfigJson.getArray(obj, "friends");
                if (array != null) {
                    for (JsonElement element : array) {
                        Friend f = fromJson(element);
                        if (f != null) {
                            cfg.friends.put(f.name.toLowerCase(Locale.US), f);
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
        String name = ConfigJson.getString(obj, "name", null);
        if (name == null || name.isBlank()) {
            return null;
        }
        Friend f = new Friend(name);
        f.note = ConfigJson.getString(obj, "note", "");
        f.firstSeenAtMs = ConfigJson.getLong(obj, "firstSeenAtMs", 0L);
        f.activity = ConfigJson.getString(obj, "activity", null);
        f.activityAtMs = ConfigJson.getLong(obj, "activityAtMs", 0L);
        return f;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("everSynced", everSynced);
            obj.addProperty("lastSyncedAtMs", lastSyncedAtMs);
            obj.addProperty("lastSyncTruncated", lastSyncTruncated);
            JsonArray array = new JsonArray();
            for (Friend f : friends.values()) {
                JsonObject entry = new JsonObject();
                entry.addProperty("name", f.name);
                entry.addProperty("note", f.note == null ? "" : f.note);
                entry.addProperty("firstSeenAtMs", f.firstSeenAtMs);
                if (f.activity != null) {
                    entry.addProperty("activity", f.activity);
                    entry.addProperty("activityAtMs", f.activityAtMs);
                }
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

    public boolean isEverSynced() {
        return everSynced;
    }

    public long getLastSyncedAtMs() {
        return lastSyncedAtMs;
    }

    public boolean isLastSyncTruncated() {
        return lastSyncTruncated;
    }

    /** Read-only snapshot in Hypixel's own last-seen listing order. */
    public List<Friend> friends() {
        return List.copyOf(friends.values());
    }

    public Friend byName(String name) {
        return name == null ? null : friends.get(name.toLowerCase(Locale.US));
    }

    public void setNote(String name, String note) {
        Friend f = byName(name);
        if (f != null) {
            f.note = note == null ? "" : note;
        }
    }

    /**
     * Replaces the whole mirrored list with what {@link FriendsListSync} just parsed off a real {@code /fl} -
     * always a full replace, never a merge, so this can never drift from what Hypixel just said (an add/remove
     * made elsewhere - another client, the Hypixel website - shows up on the very next sync same as one made
     * through this mod). Existing {@link Friend#note}/{@link Friend#firstSeenAtMs} survive a re-sync by name
     * match; {@link Friend#uuid}/{@link Friend#onlineHint} are set directly on the same in-place object by the
     * caller (see {@link FriendsListSync#applySync}), not through this method.
     *
     * @param namesInOrder Hypixel's own listing order for this sync.
     * @param truncated    see {@link #lastSyncTruncated}'s doc.
     * @return the (possibly reused) {@link Friend} objects in the same order, for the caller to attach
     *         {@code uuid}/{@code onlineHint} to.
     */
    public List<Friend> applyRealSync(List<String> namesInOrder, boolean truncated) {
        Map<String, Friend> next = new LinkedHashMap<>();
        List<Friend> out = new ArrayList<>(namesInOrder.size());
        long now = System.currentTimeMillis();
        for (String name : namesInOrder) {
            if (name == null || name.isBlank()) {
                continue;
            }
            String key = name.toLowerCase(Locale.US);
            Friend f = friends.get(key);
            if (f == null) {
                f = new Friend(name);
                f.firstSeenAtMs = now;
            } else {
                f.name = name; // keep Hypixel's latest-cased spelling
            }
            next.put(key, f);
            out.add(f);
        }
        friends.clear();
        friends.putAll(next);
        everSynced = true;
        lastSyncedAtMs = now;
        lastSyncTruncated = truncated;
        return out;
    }
}
