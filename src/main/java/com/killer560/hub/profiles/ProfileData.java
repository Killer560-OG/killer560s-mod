package com.killer560.hub.profiles;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.ModPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Everything a profile carries besides the flat {@code killer560smod-*.json} setting files: crosshair presets, the
 * scoreboard background image, the cringe lines, the sim's .txt settings, and any folder or file a future feature puts
 * beside its settings. Content the player builds (routes, waypoints, layouts, imports) is shared by every profile
 * instead: {@link #SHARED_NAMES}.
 *
 * <p>killer560 (2026-10-13): "make sure profiles also brings along everything including gifs and whatnot." So the rule
 * is INCLUDE BY DEFAULT: every {@code killer560smod-*} file or folder in a feature folder of {@code config/killer560/}
 * comes along, except what {@link #EXCLUDED_NAMES} / {@link #isExcludedFileName} keep out on purpose (secrets,
 * re-downloadable or huge caches, logs, run history, the profile system itself).
 *
 * <p>Stored under {@code <profile>/data/<legacy name>/...}, keyed by the entry's own name and restored through
 * {@link ModPaths#config(String)}, so a profile made when a feature's category folder was different still lands where
 * the running build reads it. Binary files are copied byte-for-byte with their timestamps.
 *
 * <p>{@code <profile>/profile.json} is the format stamp ({@link #FORMAT}, mod version, Minecraft version, variant).
 * A profile with neither a stamp nor a data folder is from before this class (format 1): loading it changes only
 * settings and leaves the live data alone, so an old profile can never wipe his GIFs or routes.
 */
public final class ProfileData {

    private static final Logger LOGGER = ModLog.get("killer560smod-profiles");

    /** 1 = flat setting files only (before 2026-10-13). 2 = settings + {@code data/} + {@code profile.json}. */
    public static final int FORMAT = 2;
    public static final String DATA_DIR = "data";
    public static final String META_FILE = "profile.json";

    /**
     * Names (with {@link ModPaths#PREFIX} stripped) that never go in a profile, each for a reason:
     */
    static final Set<String> EXCLUDED_NAMES = Set.of(
            // The profile system itself.
            "profiles", "profiles-import", "profiles-share", "active-profile.txt",
            // /reboot's work folder holds a class file the game launches with java (and a pid file): a profile must
            // never carry or plant one. Downloads: Auto Update's staged jars, the speech model (~70 MB), the room database and the room library
            // (~13 MB), pathfinding island graphs (with fairy-soul progress beside them - his game state, not a setup).
            "autoupdate-staging", "reboot", "voice-model", "roomdata", "rooms", "pathfinding", "communityroutes.json",
            // A Chromium profile (cookies, logins): huge and holds session data.
            "shorts-profile",
            // Caches and diagnostics: GIF audio converted to WAV, the Pack Disabler's "missing models" list, sim map
            // dumps, old sim measurement output, the sim world-version marker, the generated Spotify guide.
            "gifs", "menu-themebackgrounds", "gifs-cache", "packdisabler-missing.txt", "mapdumps", "measurements.txt", "sim-world.txt",
            // The sim's F7 boss room capture (dev capture): a few MB of Hypixel's blocks, re-captured in a boss.
            "sim-bossarena.bin", "sim-bossarena-p2.bin", "sim-bossarena-p3.bin", "sim-bossarena-p4.bin", "sim-bossarena-p5.bin",
            "spotify-setup-guide.txt",
            // The Party Finder's cache of other players' dungeon stats (re-fetched on view).
            "partyfinder-stats-cache.json",
            "loadout-cache.json",
            // Whether this install has seen the first-launch tour: about the install, not a setup to share.
            "intro.json",
            // History, not setup: run history and the auto-tracked "best friends" play records (same reason the
            // Croesus and Experiments logs were always left out).
            "runs", "social",
            // The Map Designer's list of floors he played (sim-designer-1015): history like the runs.
            "sim-map-history.json",
            // Blood Rush Helper's learned spawn spots: his own sightings, history like the runs.
            "bloodrush-spots.json",
            // The Price Tooltip's downloaded prices and item facts (re-fetched every few minutes).
            "pricetooltip-cache",
            // Image Preview's downloaded pictures (fetched again on hover, size-capped).
            "imagepreview-cache",
            // The Session Recorder's "never record here" marker: it belongs to one instance (his dungeon instance) and
            // must not travel to, or be dropped from, another by a profile.
            "session-recorder-disabled",
            // The Performance HUD's stutter log and its rolled copy (stutter-1026): diagnostics of one PC.
            "lagdisplay-stutters.log", "lagdisplay-stutters.1.log",
            // an old recording of Hypixel's Auction House menus (2026-11-02, command removed): a log.
            "ahflip-menudump.txt",
            // The Armor HUD's cache of the equipment it last saw in the equipment menu (2026-11-03): game state.
            "armorhud-equipment.json"
    );

    /**
     * Content the player BUILDS, kept as ONE copy that every profile on the instance shares (profshare-1112, killer560
     * 2026-11-12: "make things like auto routes ap3 and all those with configs shared between profiles"): a profile never
     * saves it, and applying one never replaces or deletes it. Which route/config/preset is SELECTED stays a per-profile
     * setting. Names are after {@link ModPaths#PREFIX}. {@link SharedContent} folds the copies older profiles saved
     * into the live ones once.
     */
    static final Set<String> SHARED_NAMES = Set.of(
            // Recorded routes and picks: AP3 chains (and its pre-folder file), Auto Routes, Waypoint Routes, Etherwarp
            // Waypoints, Breaker Aura configs.
            "ap3", "ap3.json", "autoroutes.json", "waypointroutes-routes.json", "etherwarp-waypoints.json", "breakeraura",
            // Inventory Sorter layouts. (Crosshair presets stay per profile: a preset is a crosshair's settings, and
            // testkit 980 proves each profile keeps its own.)
            "invsort",
            // Files he imported: map check-mark sprites, fonts, menu click sounds, theme music.
            "livemap-checkmarks", "fonts", "menu-clicksounds", "menu-thememusic");

    /** Whether a top-level entry is shared content (a {@link #SHARED_NAMES} entry). */
    static boolean isShared(String name) {
        return name != null && name.startsWith(ModPaths.PREFIX)
                && SHARED_NAMES.contains(name.substring(ModPaths.PREFIX.length()));
    }

    /** Name prefixes (after {@link ModPaths#PREFIX}) that never go in: bug report zips, route-file broken backups. */
    private static final String[] EXCLUDED_NAME_PREFIXES = {"bugreport-", "autoroutes.broken", "sessionlog-",
            // Every dungeon sim file (boss, loadout, menu, coop, filters, speed, keys...): his own sim setup, the same in every profile.
            "sim-"};

    /** Paths inside an included folder that are left out: the custom scoreboard's scraped bits/powder cache. */
    private static final Set<String> EXCLUDED_INNER = Set.of("killer560smod-customscoreboard/data.json");

    /** Per-profile cap on carried data, so an import cannot fill the disk. */
    static final long MAX_TOTAL_BYTES = 512L * 1024 * 1024;

    private ProfileData() {
    }


    /** Logs, corrupt/broken backups and temp files are never carried, wherever they sit. */
    static boolean isExcludedFileName(String fileName) {
        String n = fileName.toLowerCase(Locale.ROOT);
        return n.endsWith(".log") || n.contains(".corrupt") || n.contains(".broken") || n.endsWith(".tmp")
                || n.endsWith(".bak") || n.endsWith(".part");
    }

    /** Whether a name (prefix stripped) is on the never-carried lists, by name or by prefix. */
    static boolean isExcludedKey(String key) {
        if (EXCLUDED_NAMES.contains(key)) return true;
        for (String prefix : EXCLUDED_NAME_PREFIXES) {
            if (key.startsWith(prefix)) return true;
        }
        return false;
    }

    /** Whether a top-level entry name (a file or folder directly in a feature folder) is profile data. Setting files
     *  ({@code killer560smod-*.json} plain files) are NOT data - {@link ProfileManager} handles them. */
    static boolean isDataName(String name, boolean isDirectory) {
        if (name == null || !name.startsWith(ModPaths.PREFIX) || name.contains("/") || name.contains("\\")
                || name.equals(".") || name.equals("..")) {
            return false;
        }
        if (!isDirectory && name.endsWith(".json")) return false;
        String key = name.substring(ModPaths.PREFIX.length());
        if (isShared(name) || ModPaths.isRetired(name)) return false;
        return !isExcludedKey(key) && !isExcludedFileName(name);
    }

    /** Whether {@code rel} ("entry" or "entry/sub/file", forward slashes) may be carried. */
    static boolean isCarriedRelative(String rel) {
        if (EXCLUDED_INNER.contains(rel)) return false;
        for (String segment : rel.split("/")) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..") || isExcludedFileName(segment)) return false;
        }
        return true;
    }


    /** Every live data entry by name: {@code config/killer560/<category>/<feature>/<name>}. Where one name appears
     *  in two folders (a leftover from an older layout), the one {@link ModPaths#config} reads wins. */
    static Map<String, Path> liveEntries() {
        Map<String, Path> out = new TreeMap<>();
        Path root = ModPaths.root();
        if (!Files.isDirectory(root)) return out;
        try (var walk = Files.walk(root, 3)) {
            // Never retired/ (a removed feature's leftovers, ModPaths.RETIRED_NAMES): a profile must not carry it.
            walk.filter(p -> root.relativize(p).getNameCount() == 3
                    && !root.relativize(p).getName(0).toString().equals(ModPaths.RETIRED)).forEach(p -> {
                String name = p.getFileName().toString();
                if (!isDataName(name, Files.isDirectory(p))) return;
                Path existing = out.get(name);
                if (existing == null || p.equals(ModPaths.config(name))) {
                    out.put(name, p);
                }
            });
        } catch (Exception e) {
            LOGGER.warn("[Profiles] could not list data under {}", root, e);
        }
        return out;
    }

    /** Every live data FILE: relative path ("name" or "name/sub/file") -> path. */
    static Map<String, Path> liveFiles() {
        Map<String, Path> out = new TreeMap<>();
        for (Map.Entry<String, Path> e : liveEntries().entrySet()) {
            collectFiles(e.getKey(), e.getValue(), out);
        }
        return out;
    }

    /** Every file under a profile's {@code data/} folder, the same way. */
    static Map<String, Path> storedFiles(Path profileDir) {
        Map<String, Path> out = new TreeMap<>();
        Path data = profileDir.resolve(DATA_DIR);
        if (!Files.isDirectory(data)) return out;
        try (var stream = Files.list(data)) {
            stream.forEach(p -> {
                String name = p.getFileName().toString();
                if (isDataName(name, Files.isDirectory(p))) {
                    collectFiles(name, p, out);
                }
            });
        } catch (IOException e) {
            LOGGER.warn("[Profiles] could not list {}", data, e);
        }
        return out;
    }

    private static void collectFiles(String name, Path entry, Map<String, Path> out) {
        if (Files.isRegularFile(entry)) {
            if (isCarriedRelative(name)) {
                out.put(name, entry);
            }
            return;
        }
        if (!Files.isDirectory(entry)) return;
        try (var walk = Files.walk(entry)) {
            walk.filter(Files::isRegularFile).forEach(f -> {
                String rel = name + "/" + entry.relativize(f).toString().replace('\\', '/');
                if (isCarriedRelative(rel)) {
                    out.put(rel, f);
                }
            });
        } catch (IOException e) {
            LOGGER.warn("[Profiles] could not list {}", entry, e);
        }
    }

    private static boolean same(Path a, Path b) {
        try {
            return Files.isRegularFile(b) && Files.size(a) == Files.size(b)
                    && Files.getLastModifiedTime(a).equals(Files.getLastModifiedTime(b));
        } catch (IOException e) {
            return false;
        }
    }

    private static void copyFile(Path from, Path to) throws IOException {
        Files.createDirectories(to.getParent());
        Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
    }


    /** Makes {@code <profile>/data} an exact copy of the live data (Save). Returns the number of files carried. */
    static int snapshot(Path profileDir) throws IOException {
        Map<String, Path> live = liveFiles();
        Path data = profileDir.resolve(DATA_DIR);
        Map<String, Path> stored = storedFiles(profileDir);
        for (Map.Entry<String, Path> e : live.entrySet()) {
            Path target = data.resolve(e.getKey());
            if (!same(e.getValue(), target)) {
                copyFile(e.getValue(), target);
            }
        }
        for (String gone : stored.keySet()) {
            if (!live.containsKey(gone)) {
                Files.deleteIfExists(stored.get(gone));
            }
        }
        // Anything in data/ that is not carried (an excluded name from another build, emptied folders) goes too, but an
        // older save's copy of shared content stays: it is his only record of what that profile once held.
        if (Files.isDirectory(data)) {
            try (var stream = Files.list(data)) {
                for (Path p : stream.toList()) {
                    String name = p.getFileName().toString();
                    if (!isDataName(name, Files.isDirectory(p)) && !isShared(name)) {
                        deleteRecursive(p);
                    }
                }
            }
            pruneEmptyDirs(data);
            try (var s = Files.list(data)) {
                if (s.findAny().isEmpty()) {
                    Files.delete(data);
                }
            }
        }
        // The stamp alone marks a profile that carries data, so an empty data/ folder is never left behind.
        writeMeta(profileDir);
        return live.size();
    }

    /**
     * The auto-save half: copies live data files that differ from the profile's copy, and deletes from the profile
     * the files that were live on the previous poll ({@code previous}) and are gone now. Returns this poll's live set,
     * or {@code previous} untouched when nothing could be listed. Counts are added to {@code counts[0]} (copied) and
     * {@code counts[1]} (removed).
     */
    static Set<String> sync(Path profileDir, Set<String> previous, int[] counts) {
        Map<String, Path> live = liveFiles();
        Path data = profileDir.resolve(DATA_DIR);
        boolean stamped = Files.isRegularFile(profileDir.resolve(META_FILE));
        for (Map.Entry<String, Path> e : live.entrySet()) {
            Path target = data.resolve(e.getKey());
            if (same(e.getValue(), target)) continue;
            try {
                copyFile(e.getValue(), target);
                counts[0]++;
            } catch (IOException ignored) {
                // Mid-write by its feature; the next poll picks it up.
            }
        }
        if (previous != null) {
            for (String gone : previous) {
                if (!live.containsKey(gone)) {
                    try {
                        if (Files.deleteIfExists(data.resolve(gone))) {
                            counts[1]++;
                        }
                    } catch (IOException ignored) {
                    }
                }
            }
            if (counts[1] > 0) {
                pruneEmptyDirs(data);
            }
        }
        if (!stamped || counts[0] > 0 || counts[1] > 0) {
            try {
                writeMeta(profileDir);
            } catch (IOException ignored) {
            }
        }
        return new HashSet<>(live.keySet());
    }


    /** Whether this profile carries data (format 2 or later) - a format-1 profile leaves the live data alone. */
    static boolean carriesData(Path profileDir) {
        return Files.isDirectory(profileDir.resolve(DATA_DIR)) || Files.isRegularFile(profileDir.resolve(META_FILE));
    }

    /**
     * Replaces the live data with the profile's (Load): every carried live entry the profile does not have is
     * removed, and every entry it has is put back exactly. Returns {copied files, removed entries, failures}, or null
     * for a format-1 profile (nothing touched).
     */
    static int[] apply(Path profileDir) {
        if (!carriesData(profileDir)) return null;
        int[] result = new int[3];
        Map<String, Path> stored = storedFiles(profileDir);
        Set<String> storedEntries = new HashSet<>();
        for (String rel : stored.keySet()) {
            int slash = rel.indexOf('/');
            storedEntries.add(slash < 0 ? rel : rel.substring(0, slash));
        }
        // 1. Clear every live carried entry - also the ones the profile has, so a file removed from it goes too.
        for (Map.Entry<String, Path> e : liveEntries().entrySet()) {
            try {
                if (Files.isDirectory(e.getValue())) {
                    clearCarried(e.getKey(), e.getValue());
                } else if (isCarriedRelative(e.getKey())) {
                    Files.deleteIfExists(e.getValue());
                }
                if (!storedEntries.contains(e.getKey())) {
                    result[1]++;
                }
            } catch (IOException ex) {
                result[2]++;
                LOGGER.warn("[Profiles] could not clear {}", e.getValue(), ex);
            }
        }
        // 2. Copy the profile's files in, each entry where the running build reads it.
        Map<String, Path> targets = new LinkedHashMap<>();
        for (Map.Entry<String, Path> e : stored.entrySet()) {
            String rel = e.getKey();
            int slash = rel.indexOf('/');
            String name = slash < 0 ? rel : rel.substring(0, slash);
            Path base = targets.computeIfAbsent(name, ModPaths::config);
            Path target = slash < 0 ? base : base.resolve(rel.substring(slash + 1));
            try {
                copyFile(e.getValue(), target);
                result[0]++;
            } catch (IOException ex) {
                result[2]++;
                LOGGER.warn("[Profiles] could not restore {}", target, ex);
            }
        }
        return result;
    }

    /** Deletes the carried files inside a live folder, leaving the excluded ones (a cache file beside them). */
    private static void clearCarried(String name, Path dir) throws IOException {
        Map<String, Path> files = new TreeMap<>();
        collectFiles(name, dir, files);
        for (Path f : files.values()) {
            Files.deleteIfExists(f);
        }
        pruneEmptyDirs(dir);
    }

    /** Keeps only the named entries in a profile's data (New Blank). */
    static void keepOnly(Path profileDir, Set<String> names) throws IOException {
        Path data = profileDir.resolve(DATA_DIR);
        if (!Files.isDirectory(data)) return;
        try (var stream = Files.list(data)) {
            for (Path p : stream.toList()) {
                if (!names.contains(p.getFileName().toString())) {
                    deleteRecursive(p);
                }
            }
        }
    }


    /** The stamp: format, mod version, Minecraft version, variant, testing, commit, when. */
    static void writeMeta(Path profileDir) throws IOException {
        JsonObject o = new JsonObject();
        o.addProperty("format", FORMAT);
        o.addProperty("modVersion", com.killer560.hub.configversion.ConfigMigrations.currentVersion());
        String mc = com.killer560.hub.autoupdate.BuildInfo.minecraft();
        o.addProperty("minecraft", mc == null ? "unknown" : mc);
        o.addProperty("variant", com.killer560.hub.autoupdate.BuildInfo.variant());
        o.addProperty("testing", com.killer560.hub.BuildVariant.TESTING);
        o.addProperty("commit", com.killer560.hub.autoupdate.BuildInfo.shortCommit());
        o.addProperty("savedAt", System.currentTimeMillis());
        Files.createDirectories(profileDir);
        Files.writeString(profileDir.resolve(META_FILE), new GsonBuilder().setPrettyPrinting().create().toJson(o),
                StandardCharsets.UTF_8);
    }

    /** What a profile's stamp says. {@code format} 1 and nulls for a profile from before the stamp. */
    public record Meta(int format, String modVersion, String minecraft, String variant, boolean testing) {
        String describe() {
            if (format <= 1) return "an older mod build";
            return (modVersion == null ? "?" : modVersion) + " / " + (minecraft == null ? "?" : minecraft) + " "
                    + (variant == null ? "?" : variant) + (testing ? " testing" : "");
        }
    }

    public static Meta readMeta(Path profileDir) { return parseMeta(profileDir.resolve(META_FILE)); }

    static Meta parseMeta(Path file) {
        if (!Files.isRegularFile(file)) return new Meta(1, null, null, null, false);
        try {
            return parseMeta(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return new Meta(FORMAT, null, null, null, false);
        }
    }

    static Meta parseMeta(String json) {
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            int format = o.has("format") && o.get("format").isJsonPrimitive() ? o.get("format").getAsInt() : FORMAT;
            return new Meta(format, str(o, "modVersion"), str(o, "minecraft"), str(o, "variant"),
                    o.has("testing") && o.get("testing").isJsonPrimitive() && o.get("testing").getAsBoolean());
        } catch (RuntimeException e) {
            // A stamp nobody can read still means "this profile carries data".
            return new Meta(FORMAT, null, null, null, false);
        }
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : null;
    }


    static void deleteRecursive(Path path) throws IOException {
        if (!Files.exists(path)) return;
        if (!Files.isDirectory(path)) {
            Files.deleteIfExists(path);
            return;
        }
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** Removes empty folders below {@code dir} (not {@code dir} itself). */
    private static void pruneEmptyDirs(Path dir) {
        if (!Files.isDirectory(dir)) return;
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException exc) {
                    if (!d.equals(dir)) {
                        try (var s = Files.list(d)) {
                            if (s.findAny().isEmpty()) {
                                Files.delete(d);
                            }
                        } catch (IOException ignored) {
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
        }
    }
}
