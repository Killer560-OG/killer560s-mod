package com.killer560.hub.pathfinding;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The per-profile "which fairy souls have I already found" log, persisted across restarts.
 * <p>
 * Lives in {@code config/killer560smod-pathfinding/fairy-souls.json} - deliberately inside the feature's own folder
 * rather than as a {@code config/killer560smod-*.json} file, because {@code profiles/ProfileManager} snapshots and
 * overwrites every file matching that name pattern, and this is real run data, not a setting.
 * <p>
 * Two sources feed it, the way SkyHanni's {@code FastFairySoulsPathfinder} does it:
 * <ul>
 * <li>Chat: "SOUL! You found a Fairy Soul!" / "You have already found that Fairy Soul!" mark the closest known soul.</li>
 * <li>Hypixel's own "Fairy Souls Guide" menu: each island item's "Fairy Souls: found/total" lore line. A complete
 * island marks every soul on it found, an island at 0 clears it, and a partially found island keeps the per-soul
 * records and is flagged as partially known (the menu does not say WHICH souls were found).</li>
 * </ul>
 * Positions are stored as the graph node's block position; lookups match within 3 blocks so a small graph update does
 * not lose the record.
 */
public final class FairySoulStore {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-pathfinding");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final double MATCH_DISTANCE_SQ = 9.0;

    private static final Path FILE = FabricLoader.getInstance().getConfigDir()
            .resolve("killer560smod-pathfinding").resolve("fairy-souls.json");

    /** One island's record inside one profile. */
    public static final class IslandRecord {
        public final List<int[]> found = new ArrayList<>();
        /** Last "found" number Hypixel's menu showed for this island, or -1. */
        public int menuFound = -1;
        /** Last "total" number Hypixel's menu showed, or -1. */
        public int menuTotal = -1;
        public long menuAt = 0L;

        public boolean menuComplete() {
            return menuTotal > 0 && menuFound >= menuTotal;
        }
    }

    private static final Map<String, Map<String, IslandRecord>> PROFILES = new LinkedHashMap<>();
    private static final Map<String, String> PROFILE_NAMES = new LinkedHashMap<>();
    private static final Map<String, String> PROFILE_IDS = new LinkedHashMap<>();
    private static boolean loaded = false;

    private FairySoulStore() {
    }

    public static synchronized void load() {
        PROFILES.clear();
        PROFILE_NAMES.clear();
        PROFILE_IDS.clear();
        loaded = true;
        if (!Files.exists(FILE)) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(FILE, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject profiles = ConfigJson.getObject(root, "profiles");
            if (profiles == null) {
                return;
            }
            for (String key : profiles.keySet()) {
                JsonObject profile = ConfigJson.getObject(profiles, key);
                if (profile == null) {
                    continue;
                }
                PROFILE_NAMES.put(key, ConfigJson.getString(profile, "name", ""));
                PROFILE_IDS.put(key, ConfigJson.getString(profile, "profileId", ""));
                Map<String, IslandRecord> islands = new LinkedHashMap<>();
                JsonObject islandsJson = ConfigJson.getObject(profile, "islands");
                if (islandsJson != null) {
                    for (String island : islandsJson.keySet()) {
                        JsonObject rec = ConfigJson.getObject(islandsJson, island);
                        if (rec == null) {
                            continue;
                        }
                        IslandRecord record = new IslandRecord();
                        record.menuFound = ConfigJson.getInt(rec, "menuFound", -1);
                        record.menuTotal = ConfigJson.getInt(rec, "menuTotal", -1);
                        record.menuAt = ConfigJson.getLong(rec, "menuAt", 0L);
                        JsonArray found = ConfigJson.getArray(rec, "found");
                        if (found != null) {
                            for (JsonElement el : found) {
                                int[] pos = parsePos(el.isJsonPrimitive() ? el.getAsString() : null);
                                if (pos != null) {
                                    record.found.add(pos);
                                }
                            }
                        }
                        islands.put(island, record);
                    }
                }
                PROFILES.put(key, islands);
            }
        } catch (Exception e) {
            LOGGER.warn("[Pathfinding] Could not read the fairy soul log ({}) - starting a fresh one", e.toString());
            PROFILES.clear();
        }
    }

    public static synchronized void save() {
        try {
            Files.createDirectories(FILE.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("version", 1);
            JsonObject profiles = new JsonObject();
            for (Map.Entry<String, Map<String, IslandRecord>> e : PROFILES.entrySet()) {
                JsonObject profile = new JsonObject();
                profile.addProperty("name", PROFILE_NAMES.getOrDefault(e.getKey(), ""));
                profile.addProperty("profileId", PROFILE_IDS.getOrDefault(e.getKey(), ""));
                JsonObject islands = new JsonObject();
                for (Map.Entry<String, IslandRecord> ie : e.getValue().entrySet()) {
                    JsonObject rec = new JsonObject();
                    JsonArray found = new JsonArray();
                    for (int[] pos : ie.getValue().found) {
                        found.add(pos[0] + ":" + pos[1] + ":" + pos[2]);
                    }
                    rec.add("found", found);
                    rec.addProperty("menuFound", ie.getValue().menuFound);
                    rec.addProperty("menuTotal", ie.getValue().menuTotal);
                    rec.addProperty("menuAt", ie.getValue().menuAt);
                    islands.add(ie.getKey(), rec);
                }
                profile.add("islands", islands);
                profiles.add(e.getKey(), profile);
            }
            root.add("profiles", profiles);
            Files.writeString(FILE, GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.warn("[Pathfinding] Could not save the fairy soul log: {}", e.toString());
        }
    }

    private static synchronized Map<String, IslandRecord> profile(String key) {
        if (!loaded) {
            load();
        }
        return PROFILES.computeIfAbsent(key, k -> new LinkedHashMap<>());
    }

    public static synchronized IslandRecord record(String profileKey, String island) {
        return profile(profileKey).computeIfAbsent(island, k -> new IslandRecord());
    }

    /** Moves anything logged before the profile name was known onto the real profile. */
    static synchronized void onProfileChanged(String oldKey, String newKey, String name, String id) {
        if (!loaded) {
            load();
        }
        PROFILE_NAMES.put(newKey, name);
        if (id != null && !id.isEmpty()) {
            PROFILE_IDS.put(newKey, id);
        }
        if (oldKey != null && !oldKey.equals(newKey) && oldKey.endsWith("/?")) {
            Map<String, IslandRecord> pending = PROFILES.remove(oldKey);
            if (pending != null && !pending.isEmpty()) {
                Map<String, IslandRecord> target = profile(newKey);
                for (Map.Entry<String, IslandRecord> e : pending.entrySet()) {
                    IslandRecord into = target.computeIfAbsent(e.getKey(), k -> new IslandRecord());
                    for (int[] pos : e.getValue().found) {
                        addIfNew(into, pos);
                    }
                }
                save();
            }
        }
    }

    /** @return true when this soul counts as found (per-soul record, or the whole island complete in the menu). */
    public static synchronized boolean isFound(String profileKey, String island, double x, double y, double z) {
        Map<String, IslandRecord> islands = profile(profileKey);
        IslandRecord group = islands.get(IslandDetector.soulMenuGroup(island));
        if (group != null && group.menuComplete()) {
            return true;
        }
        IslandRecord record = islands.get(island);
        if (record == null) {
            return false;
        }
        for (int[] pos : record.found) {
            double dx = pos[0] - x;
            double dy = pos[1] - y;
            double dz = pos[2] - z;
            if (dx * dx + dy * dy + dz * dz <= MATCH_DISTANCE_SQ) {
                return true;
            }
        }
        return false;
    }

    /** @return true when this is a new record (i.e. something actually changed). */
    public static synchronized boolean markFound(String profileKey, String island, double x, double y, double z) {
        IslandRecord record = record(profileKey, island);
        int[] pos = {(int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z)};
        if (!addIfNew(record, pos)) {
            return false;
        }
        save();
        return true;
    }

    private static boolean addIfNew(IslandRecord record, int[] pos) {
        for (int[] existing : record.found) {
            double dx = existing[0] - pos[0];
            double dy = existing[1] - pos[1];
            double dz = existing[2] - pos[2];
            if (dx * dx + dy * dy + dz * dz <= MATCH_DISTANCE_SQ) {
                return false;
            }
        }
        record.found.add(pos);
        return true;
    }

    /** Applies one island line of Hypixel's "Fairy Souls Guide" menu. @return a short description of what changed. */
    public static synchronized String applyMenu(String profileKey, String menuGroup, int found, int total,
                                                List<IslandGraph> graphs) {
        IslandRecord group = record(profileKey, menuGroup);
        group.menuFound = found;
        group.menuTotal = total;
        group.menuAt = System.currentTimeMillis();
        String result;
        if (total > 0 && found >= total) {
            int added = 0;
            for (IslandGraph graph : graphs) {
                IslandRecord record = record(profileKey, graph.island);
                for (IslandGraph.Node soul : graph.withTag(IslandGraph.TAG_FAIRY_SOUL)) {
                    if (addIfNew(record, new int[]{(int) soul.x, (int) soul.y, (int) soul.z})) {
                        added++;
                    }
                }
            }
            result = "complete" + (added > 0 ? " (+" + added + ")" : "");
        } else if (found <= 0) {
            for (String island : IslandDetector.graphsForSoulGroup(menuGroup)) {
                IslandRecord record = profile(profileKey).get(island);
                if (record != null) {
                    record.found.clear();
                }
            }
            result = "none found";
        } else {
            result = "partial " + found + "/" + total;
        }
        save();
        return result;
    }

    /** How many souls of {@code island} are logged as found (ignoring the menu's own total). */
    public static synchronized int foundCount(String profileKey, String island) {
        IslandRecord record = profile(profileKey).get(island);
        return record == null ? 0 : record.found.size();
    }

    /** Clears one island (and its menu counts) for this profile. */
    public static synchronized void resetIsland(String profileKey, String island) {
        for (String key : IslandDetector.graphsForSoulGroup(IslandDetector.soulMenuGroup(island))) {
            IslandRecord record = profile(profileKey).get(key);
            if (record != null) {
                record.found.clear();
                record.menuFound = -1;
                record.menuTotal = -1;
                record.menuAt = 0L;
            }
        }
        IslandRecord group = profile(profileKey).get(IslandDetector.soulMenuGroup(island));
        if (group != null) {
            group.menuFound = -1;
            group.menuTotal = -1;
        }
        save();
    }

    /** Clears every island for this profile. */
    public static synchronized void resetProfile(String profileKey) {
        PROFILES.remove(profileKey);
        save();
    }

    private static int[] parsePos(String s) {
        if (s == null) {
            return null;
        }
        String[] parts = s.split(":");
        if (parts.length != 3) {
            return null;
        }
        try {
            return new int[]{Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()),
                    Integer.parseInt(parts[2].trim())};
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
