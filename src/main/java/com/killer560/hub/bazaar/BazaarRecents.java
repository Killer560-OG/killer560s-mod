package com.killer560.hub.bazaar;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.pathfinding.ProfileTracker;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.ModPaths;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Bazaar screen's left rail: the products he recently opened or searched for, most recent first, per SkyBlock
 * profile ({@link ProfileTracker#key()}, the same key Manage Orders' snapshot uses). Real data, not a setting, so the
 * file is kept out of setting profiles. Saved on every change: a change only ever comes from his own click.
 */
public final class BazaarRecents {

    private static final Logger LOGGER = ModLog.get("killer560smod-bazaar");
    private static final Path PATH = ModPaths.config("killer560smod-bazaar-recent.json");
    /** How many each profile keeps. */
    public static final int MAX = 16;

    private static final Map<String, List<String>> BY_PROFILE = new LinkedHashMap<>();
    private static boolean loaded;

    private BazaarRecents() {
    }

    /** Product ids, most recent first, for the profile he is on now. */
    public static synchronized List<String> list() {
        load();
        List<String> l = BY_PROFILE.get(ProfileTracker.key());
        return l == null ? List.of() : List.copyOf(l);
    }

    /** Moves {@code productId} to the top of the current profile's list (adding it when new). */
    public static synchronized void add(String productId) {
        if (productId == null || productId.isBlank()) {
            return;
        }
        load();
        List<String> l = BY_PROFILE.computeIfAbsent(ProfileTracker.key(), k -> new ArrayList<>());
        if (!l.isEmpty() && l.get(0).equals(productId)) {
            return;
        }
        l.remove(productId);
        l.add(0, productId);
        while (l.size() > MAX) {
            l.remove(l.size() - 1);
        }
        save();
    }

    /** Testkit: forget every profile's list (memory and file). */
    public static synchronized void clearForTest() {
        load();
        BY_PROFILE.clear();
        save();
    }

    private static void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        if (!Files.isRegularFile(PATH)) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(PATH, StandardCharsets.UTF_8)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> e : root.entrySet()) {
                if (!e.getValue().isJsonArray()) {
                    continue;
                }
                List<String> ids = new ArrayList<>();
                for (JsonElement id : e.getValue().getAsJsonArray()) {
                    if (id.isJsonPrimitive() && ids.size() < MAX && !ids.contains(id.getAsString())) {
                        ids.add(id.getAsString());
                    }
                }
                BY_PROFILE.put(e.getKey(), ids);
            }
        } catch (Exception ex) {
            LOGGER.warn("[Bazaar] recent products file unreadable, starting empty", ex);
        }
    }

    private static void save() {
        try {
            JsonObject root = new JsonObject();
            for (Map.Entry<String, List<String>> e : BY_PROFILE.entrySet()) {
                JsonArray a = new JsonArray();
                e.getValue().forEach(a::add);
                root.add(e.getKey(), a);
            }
            Files.createDirectories(PATH.getParent());
            Files.writeString(PATH, new GsonBuilder().setPrettyPrinting().create().toJson(root), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            LOGGER.warn("[Bazaar] could not save recent products", ex);
        }
    }
}
