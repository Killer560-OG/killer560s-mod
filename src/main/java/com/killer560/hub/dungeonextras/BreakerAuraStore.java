package com.killer560.hub.dungeonextras;

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
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Swappable Breaker Aura configs - killer560: "breaker aura also needs an option to swap between breaker auras
 * just like the auto routes can swap. with the same easy copy and whatnot." Follows {@code ap3/Ap3Store} exactly:
 * every config is its own file in {@code config/killer560smod-breakeraura/}, exactly one is active at a time
 * ({@link DungeonExtrasConfig#getBreakerAuraConfigFile()}), and {@link #listConfigNames()} / {@link #select} /
 * {@link #createConfig} / {@link #delete} are what {@link BreakerAuraConfigScreen} drives.
 * <p>
 * <b>What lives in a config vs stays global</b>: only the PICKED BLOCKS - the thing killer560 actually wants to
 * swap between (one wall-set per dungeon layout, handed to a friend as one file) - live here. Reach, cooldown,
 * zero ping, auto swap and every other Breaker Aura setting stay in {@link DungeonExtrasConfig}: they describe
 * how his OWN client swings, not which blocks a floor needs broken, so they apply the same no matter which picks
 * are active - exactly the split {@code Ap3Store} (chains) draws against {@code Ap3Config} (keybinds/colours).
 * <p>
 * <b>Migration</b> (killer560 already has real picks saved): {@link DungeonExtrasConfig#getBreakerAuraSelected()}
 * - the old single {@code LinkedHashSet<String>} field this replaces - is copied into {@code default.json} the
 * first time this folder has no such file, and the old field is left exactly where it was (never written to
 * again by this class, still round-tripped by {@code DungeonExtrasConfig} itself) so nothing he already picked is
 * lost even if the new folder is deleted later.
 * <p>
 * Layout, one line per pick so a person can read or hand-edit it:
 * <pre>
 * { "version": 1,
 *   "picked": [ "53,132,142", "54,132,142", ... ] }
 * </pre>
 * Loading is defensive because this file is meant to be handed around: pick count and coordinates are capped,
 * a malformed entry is skipped (the rest still loads), and a file whose parse fails is copied aside as
 * {@code <name>.broken.json} (a differently-corrupted retry gets its own timestamped copy, same as
 * {@code autoroutes/RouteStore}'s 2026-09-16 review) and never saved over.
 */
public final class BreakerAuraStore {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-breakeraura");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    /** The folder every config file lives in, inside the config dir. */
    private static final String FOLDER_NAME = "killer560smod-breakeraura";
    /** The config in use until one is chosen. */
    public static final String DEFAULT_CONFIG_NAME = "default.json";
    private static final String JSON = ".json";
    /** A file that failed to parse is copied aside as {@code <name>.broken.json}; those never show in the chooser. */
    private static final String BROKEN_SUFFIX = ".broken.json";
    /** Letters, digits, space, dash, underscore, dot - a name Windows, Discord and Notepad all agree on. */
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9 _\\-.]+");
    public static final int MAX_CONFIG_NAME = 40;
    private static final int FORMAT_VERSION = 1;

    // Proportionate caps for a friend's file, not a network input.
    /** A whole floor's worth of walls, picked block by block, with headroom to spare. */
    public static final int MAX_PICKED = 4000;
    /** Real dungeon instances sit well inside this; anything past it is garbage (or a hand-edit typo). */
    public static final long MAX_ABS_COORD = 30_000_000L;
    public static final long MIN_Y = -2048L;
    public static final long MAX_Y = 2048L;

    private static BreakerAuraStore instance;

    private final LinkedHashSet<String> picked = new LinkedHashSet<>();
    /** Set when the last load could not parse the file: {@link #save()} refuses to overwrite it until a load
     *  succeeds or the user explicitly picks/unpicks a block (which starts from the recovered/empty state they
     *  can see). */
    private boolean parseFailed;
    /** Bumped on every load, select and pick/unpick/clear - the renderer and the planner cache off this rather
     *  than the Set's identity, so a config switch (same active Set instance never swapped, only its contents
     *  reloaded) is never missed. See {@code BreakerAuraFeature#selectedBlocks}. */
    private static int version;

    private BreakerAuraStore() {
    }

    public static BreakerAuraStore getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    /** Bumped on every mutation of the active config (load, select, pick, unpick, clear). */
    public static int version() {
        return version;
    }

    /** The folder every config file lives in (the tab's "Open Folder" button); created if missing. */
    public static Path directory() {
        Path dir = FabricLoader.getInstance().getConfigDir().resolve(FOLDER_NAME);
        try {
            Files.createDirectories(dir);
        } catch (Exception ignored) {
        }
        return dir;
    }

    /** The config file in use: {@link DungeonExtrasConfig#getBreakerAuraConfigFile()} inside {@link #directory()}. */
    public static Path file() {
        return directory().resolve(DungeonExtrasConfig.getInstance().getBreakerAuraConfigFile());
    }

    /** {@code /breakeraura reload}: re-read the file, replacing what is in memory. */
    public static void reload() {
        load();
    }

    // ------------------------------------------------------------------------------------------- choosing a file

    /** Every config file in the folder by name ({@code *.json}, the broken backups left out), sorted, and always
     *  including the one in use even when it is not on disk yet (it is written on the first pick/save). */
    public static List<String> listConfigNames() {
        List<String> out = new ArrayList<>();
        try (var stream = Files.list(directory())) {
            stream.forEach(p -> {
                String name = p.getFileName().toString();
                if (Files.isRegularFile(p) && name.toLowerCase(Locale.ROOT).endsWith(JSON)
                        && !name.toLowerCase(Locale.ROOT).endsWith(BROKEN_SUFFIX)
                        && validateConfigName(name) == null) {
                    out.add(name);
                }
            });
        } catch (Exception e) {
            LOGGER.warn("[BreakerAura] Could not list {}", directory(), e);
        }
        String active = DungeonExtrasConfig.getInstance().getBreakerAuraConfigFile();
        if (out.stream().noneMatch(n -> n.equalsIgnoreCase(active))) {
            out.add(active);
        }
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    /** Trims, strips control characters and adds {@code .json} when it is missing; null for nothing at all. */
    public static String normalizeConfigName(String raw) {
        if (raw == null) {
            return null;
        }
        String t = raw.strip().replaceAll("[\\p{Cntrl}]", "");
        if (t.isEmpty()) {
            return null;
        }
        if (!t.toLowerCase(Locale.ROOT).endsWith(JSON)) {
            t = t + JSON;
        }
        return t;
    }

    /**
     * Why {@code name} (already {@link #normalizeConfigName normalized}) cannot be a config file name, or null
     * when it can: a plain file name only - no separators, no "..", no leading dot, safe characters, a sane
     * length. This is the only rule between a typed name and a path under the config folder.
     */
    public static String validateConfigName(String name) {
        if (name == null || name.isBlank()) {
            return "Type a name first.";
        }
        String stem = name.substring(0, name.length() - JSON.length());
        if (stem.isBlank()) {
            return "Type a name first.";
        }
        if (stem.length() > MAX_CONFIG_NAME) {
            return "Keep it under " + MAX_CONFIG_NAME + " characters.";
        }
        if (name.contains("/") || name.contains("\\") || name.contains("..") || stem.startsWith(".")
                || !SAFE_NAME.matcher(name).matches()) {
            return "Letters, digits, spaces, - _ and . only.";
        }
        if (name.toLowerCase(Locale.ROOT).endsWith(BROKEN_SUFFIX)) {
            return "That name is reserved for backups.";
        }
        return null;
    }

    /**
     * Creates an EMPTY config called {@code rawName} and switches to it. Refuses (with the reason) a bad name or
     * one that already exists - case-insensitively, since the folder is on a Windows disk. @return the error, or
     * null on success.
     */
    public static String createConfig(String rawName) {
        String name = normalizeConfigName(rawName);
        String error = validateConfigName(name);
        if (error != null) {
            return error;
        }
        for (String existing : listConfigNames()) {
            if (existing.equalsIgnoreCase(name)) {
                return "A config called " + existing + " already exists.";
            }
        }
        Path file = directory().resolve(name);
        if (Files.exists(file)) {
            return "A file called " + name + " already exists.";
        }
        try {
            Files.writeString(file, GSON.toJson(emptyRoot()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.warn("[BreakerAura] Could not create {}", file, e);
            return "Could not write the file (see the log).";
        }
        LOGGER.info("[BreakerAura] Created config {}", name);
        select(name);
        return null;
    }

    /**
     * Deletes a config that is NOT the one in use - killer560 asked for delete alongside list/select/create, but
     * unlike a chain a wrong delete here cannot be undone with {@code /ap3 undo}, so this refuses the two ways it
     * would go wrong silently: deleting the active config (switch away first) or the last one left (there must
     * always be something to switch back to). @return the error, or null on success.
     */
    public static String delete(String rawName) {
        String name = normalizeConfigName(rawName);
        if (name == null) {
            return "Type a name first.";
        }
        List<String> names = listConfigNames();
        String match = null;
        for (String n : names) {
            if (n.equalsIgnoreCase(name)) {
                match = n;
                break;
            }
        }
        if (match == null) {
            return "No config called " + name + ".";
        }
        String active = DungeonExtrasConfig.getInstance().getBreakerAuraConfigFile();
        if (match.equalsIgnoreCase(active)) {
            return "Switch to a different config before deleting " + match + ".";
        }
        if (names.size() <= 1) {
            return "That is the only config - nothing to delete.";
        }
        try {
            Files.deleteIfExists(directory().resolve(match));
        } catch (Exception e) {
            LOGGER.warn("[BreakerAura] Could not delete {}", match, e);
            return "Could not delete the file (see the log).";
        }
        LOGGER.info("[BreakerAura] Deleted config {}", match);
        return null;
    }

    /** Switches to the config {@code name} (a name from {@link #listConfigNames()}): persisted, then loaded. */
    public static void select(String name) {
        String clean = normalizeConfigName(name);
        if (clean == null || validateConfigName(clean) != null) {
            return;
        }
        DungeonExtrasConfig cfg = DungeonExtrasConfig.getInstance();
        if (clean.equalsIgnoreCase(cfg.getBreakerAuraConfigFile())) {
            return;
        }
        cfg.setBreakerAuraConfigFile(clean);
        cfg.save();
        LOGGER.info("[BreakerAura] Config file: {}", clean);
        load();
    }

    /** The skeleton every config file starts from - what a fresh install would write on its first save. */
    private static JsonObject emptyRoot() {
        JsonObject root = new JsonObject();
        root.addProperty("version", FORMAT_VERSION);
        root.addProperty("note", "Breaker Aura - which blocks THIS config breaks, as \"x,y,z\" world positions. "
                + "Pick/unpick in-game with the Pick Block key, or hand-edit this list, then Reload.");
        root.add("picked", new JsonArray());
        return root;
    }

    /**
     * The picks already saved in {@link DungeonExtrasConfig#getBreakerAuraSelected()} (the field this store
     * replaces), copied into {@code default.json} once so nothing he already picked is lost by the move. The old
     * field stays put (still round-tripped by {@code DungeonExtrasConfig} itself) and is never read again once
     * the copy exists - same rule {@code Ap3Store} follows for its own legacy file. Nothing is written when there
     * is nothing to preserve, so a fresh install doesn't get a clutter file before he has picked anything.
     */
    private static void migrateLegacyPicks() {
        try {
            Path target = directory().resolve(DEFAULT_CONFIG_NAME);
            if (Files.exists(target)) {
                return;
            }
            Set<String> legacy = DungeonExtrasConfig.getInstance().getBreakerAuraSelected();
            if (legacy.isEmpty()) {
                return;
            }
            JsonObject root = emptyRoot();
            JsonArray arr = new JsonArray();
            int n = 0;
            for (String k : legacy) {
                if (n >= MAX_PICKED) {
                    break;
                }
                String clean = validateKey(k);
                if (clean != null) {
                    arr.add(clean);
                    n++;
                }
            }
            root.add("picked", arr);
            Files.writeString(target, GSON.toJson(root), StandardCharsets.UTF_8);
            LOGGER.info("[BreakerAura] Migrated {} pick(s) from the old settings file into {}/{}",
                    n, FOLDER_NAME, DEFAULT_CONFIG_NAME);
        } catch (Exception e) {
            LOGGER.warn("[BreakerAura] Could not migrate the old picked blocks", e);
        }
    }

    public static void load() {
        migrateLegacyPicks();
        BreakerAuraStore store = new BreakerAuraStore();
        Path file = file();
        String fileName = file.getFileName().toString();
        if (Files.exists(file)) {
            try {
                JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
                JsonArray arr = ConfigJson.getArray(root, "picked");
                if (arr != null) {
                    for (JsonElement el : arr) {
                        if (store.picked.size() >= MAX_PICKED) {
                            LOGGER.warn("[BreakerAura] More than {} picks in {} - the rest were ignored", MAX_PICKED, fileName);
                            break;
                        }
                        if (el == null || !el.isJsonPrimitive()) {
                            continue;
                        }
                        String clean = validateKey(el.getAsString());
                        if (clean != null) {
                            store.picked.add(clean);
                        }
                    }
                }
                LOGGER.info("[BreakerAura] Loaded {} pick(s) from {}", store.picked.size(), fileName);
            } catch (Exception e) {
                store.parseFailed = true;
                store.picked.clear();
                LOGGER.warn("[BreakerAura] Failed to parse {} - backing it up, nothing will be saved over it", fileName, e);
                try {
                    Path backup = file.resolveSibling(brokenName(fileName));
                    if (!Files.exists(backup)) {
                        // One backup per broken file, not one per reload - see Ap3Store.
                        Files.copy(file, backup);
                    } else if (Files.mismatch(file, backup) != -1L) {
                        // A DIFFERENT corruption than the one already kept - same 2026-09-16 fix RouteStore got,
                        // so this one isn't silently lost the moment a pick clears parseFailed and saves over it.
                        Files.copy(file, file.resolveSibling(stem(fileName) + ".broken-" + System.currentTimeMillis() + ".json"));
                    }
                } catch (Exception backupError) {
                    LOGGER.warn("[BreakerAura] Could not back up the unreadable config file", backupError);
                }
            }
        }
        instance = store;
        version++;
    }

    /** Writes every pick. Refuses while the file on disk failed to parse and nothing has changed since, so a
     *  half-downloaded file from a friend can't be replaced by an empty one behind the user's back. */
    public void save() {
        Path file = file();
        String fileName = file.getFileName().toString();
        if (parseFailed) {
            LOGGER.warn("[BreakerAura] Not saving {}: the file on disk could not be parsed (see {})", fileName, brokenName(fileName));
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            JsonObject root = emptyRoot();
            JsonArray arr = new JsonArray();
            for (String k : picked) {
                arr.add(k);
            }
            root.add("picked", arr);
            Files.writeString(file, GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.warn("[BreakerAura] Failed to save {}", fileName, e);
        }
    }

    /** {@code default.json} -> {@code default.broken.json}. */
    private static String stem(String fileName) {
        return fileName.toLowerCase(Locale.ROOT).endsWith(JSON)
                ? fileName.substring(0, fileName.length() - JSON.length()) : fileName;
    }

    private static String brokenName(String fileName) {
        return stem(fileName) + BROKEN_SUFFIX;
    }

    // ------------------------------------------------------------------------------------------- access

    /** Every pick in the active config, as {@code "x,y,z"} keys - a snapshot copy, same as {@code Ap3Store.chains()},
     *  so a caller iterating it never sees a pick added or removed out from under it. Mutate through
     *  {@link #addPick}/{@link #removePick}/{@link #clear}. */
    public Set<String> pickedKeys() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(picked));
    }

    public boolean isPicked(String key) {
        return key != null && picked.contains(key);
    }

    /** @return true if it was added (false if it was already picked, malformed, or the cap is reached). */
    public boolean addPick(String key) {
        String clean = validateKey(key);
        if (clean == null || picked.contains(clean) || picked.size() >= MAX_PICKED) {
            return false;
        }
        picked.add(clean);
        parseFailed = false; // an explicit pick is the user's own decision to start over on a recovered store
        version++;
        return true;
    }

    /** @return true if it was picked and is now removed. */
    public boolean removePick(String key) {
        boolean removed = key != null && picked.remove(key);
        if (removed) {
            parseFailed = false;
            version++;
        }
        return removed;
    }

    /** Forgets every pick in the active config - the tab's "Clear Picked" button and {@code /breakeraura clear}.
     *  @return how many were cleared. */
    public int clear() {
        int n = picked.size();
        picked.clear();
        parseFailed = false;
        version++;
        return n;
    }

    public boolean lastLoadFailed() {
        return parseFailed;
    }

    // ------------------------------------------------------------------------------------------- helpers

    /** {@code "x,y,z"} -> the same string re-normalized (trimmed, no leading zeros/whitespace quirks), or null for
     *  anything that isn't three finite ints inside a sane world - garbage in a hand-edited or shared file must
     *  never explode the rest of the pick list. */
    static String validateKey(String raw) {
        if (raw == null) {
            return null;
        }
        String[] a = raw.trim().split(",");
        if (a.length != 3) {
            return null;
        }
        try {
            long x = Long.parseLong(a[0].trim());
            long y = Long.parseLong(a[1].trim());
            long z = Long.parseLong(a[2].trim());
            if (Math.abs(x) > MAX_ABS_COORD || y < MIN_Y || y > MAX_Y || Math.abs(z) > MAX_ABS_COORD) {
                return null;
            }
            return x + "," + y + "," + z;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
