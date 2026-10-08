package com.killer560.hub.invsort;

import com.killer560.hub.util.ModPaths;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import org.slf4j.Logger;
import com.killer560.hub.util.ModLog;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Every saved inventory layout, ONE FILE PER LAYOUT so any single one can be handed to a friend on its own -
 * same shape as {@code ap3/Ap3Store}'s chains folder ("I am fine with it being a json file as long as it is easy
 * to edit in notepad and share really easily"), just without that store's "one file active at a time" idea: every
 * layout in the folder is loaded and available by name at once, closer to how {@code autoroutes/RouteStore} keeps
 * every route addressable by name (just split one-per-file instead of one-per-room-inside-a-shared-file).
 * <p>
 * Folder: {@code config/killer560/skyblock/invsort/killer560smod-invsort/<name>.json}. Layout shape:
 * <pre>
 * { "version": 1, "name": "Dungeon Kit",
 *   "slots": { "9": "ASPECT_OF_THE_END", "10": "SPIRIT_BOOTS", ... } }
 * </pre>
 * Keys of {@code slots} are {@link net.minecraft.world.entity.player.Inventory} item indices (0-35) as strings
 * (JSON object keys are always strings); values are the Skyblock item id (or the name-fallback identity)
 * {@code autoroutes/ItemIdentity} produced when the layout was saved.
 * <p>
 * Loading is defensive because a layout file is meant to be handed around: a file whose parse fails is skipped
 * (logged, not deleted - the folder has one file per layout, so a bad file can't corrupt anyone else's) and a
 * malformed slot entry inside an otherwise good file is skipped, the rest of that layout still loads.
 */
public final class InventoryLayoutStore {

    private static final Logger LOGGER = ModLog.get("killer560smod-invsort");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FOLDER_NAME = "killer560smod-invsort";
    private static final String JSON = ".json";
    private static final int FORMAT_VERSION = 1;

    /** Letters, digits, space, dash, underscore, dot - same rule {@code Ap3Store} uses, for the same reason
     *  (a name Windows, Discord and Notepad all agree on). */
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9 _\\-.]+");
    public static final int MAX_NAME = 40;
    /** Proportionate cap for a friend's file, not a network input - one entry per managed inventory slot. */
    public static final int MAX_SLOTS = 36;

    private static InventoryLayoutStore instance;

    private final Map<String, InventoryLayout> layouts = new LinkedHashMap<>();

    private InventoryLayoutStore() {
    }

    public static InventoryLayoutStore getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    /** The folder every layout file lives in; created if missing. */
    public static Path directory() {
        Path dir = ModPaths.config(FOLDER_NAME);
        try {
            Files.createDirectories(dir);
        } catch (Exception ignored) {
        }
        return dir;
    }

    /** Re-reads every file in the folder, replacing what is in memory. */
    public static void load() {
        InventoryLayoutStore store = new InventoryLayoutStore();
        try (var stream = Files.list(directory())) {
            for (Path file : stream.filter(Files::isRegularFile).toList()) {
                String fileName = file.getFileName().toString();
                if (!fileName.toLowerCase(Locale.ROOT).endsWith(JSON)) {
                    continue;
                }
                String stem = fileName.substring(0, fileName.length() - JSON.length());
                if (validateName(stem) != null) {
                    continue; // not a name this store would ever have written - skip quietly
                }
                try {
                    JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
                    InventoryLayout layout = readLayout(stem, root);
                    if (layout != null) {
                        store.layouts.put(stem, layout);
                    }
                } catch (Exception e) {
                    LOGGER.warn("[InvSort] Skipping unreadable layout file {}: {}", fileName, e.toString());
                }
            }
        } catch (Exception e) {
            LOGGER.warn("[InvSort] Could not list {}", directory(), e);
        }
        LOGGER.info("[InvSort] Loaded {} layout(s) from {}", store.layouts.size(), directory());
        instance = store;
    }

    public static void reload() {
        load();
    }

    // ------------------------------------------------------------------------------------------- access

    /** Every layout name, sorted - the {@code /invsort list} order. */
    public List<String> listNames() {
        List<String> out = new ArrayList<>(layouts.keySet());
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    /** @return the layout by name (case-insensitive), or null. */
    public InventoryLayout get(String name) {
        if (name == null) {
            return null;
        }
        InventoryLayout exact = layouts.get(name);
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<String, InventoryLayout> e : layouts.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) {
                return e.getValue();
            }
        }
        return null;
    }

    public boolean exists(String name) {
        return get(name) != null;
    }

    /** Writes {@code layout} to its own file and keeps it in memory. @return the error, or null on success. */
    public String save(InventoryLayout layout) {
        String error = validateName(layout.name());
        if (error != null) {
            return error;
        }
        Path file = directory().resolve(layout.name() + JSON);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(writeLayout(layout)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.warn("[InvSort] Failed to save {}", file, e);
            return "Could not write the file (see the log).";
        }
        // Re-key case-insensitively: overwriting "My Kit" must replace "my kit" too, not add a second entry.
        layouts.keySet().removeIf(k -> k.equalsIgnoreCase(layout.name()));
        layouts.put(layout.name(), layout);
        return null;
    }

    /** Deletes the layout's file and its in-memory entry. @return whether anything was actually removed. */
    public boolean delete(String name) {
        InventoryLayout layout = get(name);
        if (layout == null) {
            return false;
        }
        layouts.remove(layout.name());
        try {
            Files.deleteIfExists(directory().resolve(layout.name() + JSON));
        } catch (Exception e) {
            LOGGER.warn("[InvSort] Could not delete layout file for {}", layout.name(), e);
        }
        return true;
    }

    /** Renames a layout (file and memory). @return the error, or null on success. */
    public String rename(String from, String to) {
        InventoryLayout layout = get(from);
        if (layout == null) {
            return "No layout called " + from + ".";
        }
        String error = validateName(to);
        if (error != null) {
            return error;
        }
        InventoryLayout clash = get(to);
        if (clash != null && clash != layout) {
            return "A layout called " + clash.name() + " already exists.";
        }
        String saveError = save(layout.renamed(to));
        if (saveError != null) {
            return saveError;
        }
        if (!layout.name().equals(to)) {
            layouts.remove(layout.name());
            if (!layout.name().equalsIgnoreCase(to)) {
                try {
                    Files.deleteIfExists(directory().resolve(layout.name() + JSON));
                } catch (Exception e) {
                    LOGGER.warn("[InvSort] Could not delete the old file for {}", layout.name(), e);
                }
            }
            layouts.put(to, get(to) == null ? layout.renamed(to) : get(to));
        }
        return null;
    }

    /** Why {@code name} cannot be a layout name, or null when it can - same shape as {@code Ap3Store}'s. */
    public static String validateName(String name) {
        if (name == null || name.isBlank()) {
            return "Type a name first.";
        }
        if (name.length() > MAX_NAME) {
            return "Keep it under " + MAX_NAME + " characters.";
        }
        if (name.contains("/") || name.contains("\\") || name.contains("..") || name.startsWith(".")
                || !SAFE_NAME.matcher(name).matches()) {
            return "Letters, digits, spaces, - _ and . only.";
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------- codec

    private static InventoryLayout readLayout(String stem, JsonObject root) {
        String name = ConfigJson.getString(root, "name", stem);
        if (validateName(name) != null) {
            name = stem; // a hand-edited "name" field that isn't a legal file name - fall back to the file's own
        }
        JsonObject slotsObj = ConfigJson.getObject(root, "slots");
        JsonObject iconsObj = ConfigJson.getObject(root, "icons");
        Map<Integer, String> slots = new LinkedHashMap<>();
        Map<Integer, String> icons = new LinkedHashMap<>();
        if (slotsObj != null) {
            for (String key : slotsObj.keySet()) {
                if (slots.size() >= MAX_SLOTS) {
                    LOGGER.warn("[InvSort] Layout {} has more than {} slots - the rest were ignored", name, MAX_SLOTS);
                    break;
                }
                int slot;
                try {
                    slot = Integer.parseInt(key.trim());
                } catch (NumberFormatException e) {
                    continue;
                }
                if (slot < 0 || slot > 35) {
                    continue;
                }
                String identity = ConfigJson.getString(slotsObj, key, null);
                if (identity == null || identity.isBlank()) {
                    continue;
                }
                slots.put(slot, identity.trim().toUpperCase(Locale.ROOT));
                String icon = iconsObj == null ? null : ConfigJson.getString(iconsObj, key, null);
                if (icon != null && !icon.isBlank()) {
                    icons.put(slot, icon.trim());
                }
            }
        }
        return new InventoryLayout(name, slots, icons);
    }

    private static JsonObject writeLayout(InventoryLayout layout) {
        JsonObject root = new JsonObject();
        root.addProperty("version", FORMAT_VERSION);
        root.addProperty("name", layout.name());
        root.addProperty("note", "Auto Inventory Sorter layout - slot is the inventory index (0-8 hotbar, 9-35 "
                + "main storage), value is the item's Skyblock id; icons only draw the layout in the /invsort menu.");
        JsonObject slotsObj = new JsonObject();
        for (Map.Entry<Integer, String> e : layout.entries().entrySet()) {
            slotsObj.addProperty(String.valueOf(e.getKey()), e.getValue());
        }
        root.add("slots", slotsObj);
        JsonObject iconsObj = new JsonObject();
        for (Map.Entry<Integer, String> e : layout.icons().entrySet()) {
            iconsObj.addProperty(String.valueOf(e.getKey()), e.getValue());
        }
        root.add("icons", iconsObj);
        return root;
    }
}
