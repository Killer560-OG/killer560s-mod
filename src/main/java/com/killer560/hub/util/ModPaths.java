package com.killer560.hub.util;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Where every file this mod keeps in the config folder lives. EVERY config path goes through here.
 *
 * <p>killer560 (2026-10-04): "Whenever I go into my folder for minecraft config, there are like 80 folders/files
 * for my mod. Make a folder called killer560 that all those live inside. It should have some folders inside of it
 * like dungeons and whatnot that further branch into things like ap3 or breakeraura." So instead of
 * {@code config/killer560smod-hud.json} the file is {@code config/killer560/interface/hud/killer560smod-hud.json}.
 *
 * <p>Only the LOCATION changed, never the name: a file keeps the exact name it always had, so profiles, bug reports
 * and every piece of name-based logic carry on as before, and an old file found in the config root can be moved
 * by its name alone. Which folder a name belongs in is decided by {@link #TABLE}, the one place that mapping lives.
 *
 * <p>Moving is lazy AND eager: {@link #migrateAll()} runs first thing in the client entrypoint and sweeps every old
 * file out of the config root, and {@link #config(String)} also moves its own file if it finds it still at the old
 * spot (a class initialised before the sweep, or a file the sweep could not move). A failed move never loses a
 * setting - {@link #config(String)} then hands back the OLD path, so the feature keeps reading what it always read.
 */
public final class ModPaths {

    /** The prefix every one of this mod's config names starts with. */
    public static final String PREFIX = "killer560smod-";

    /** The old {@code config/killer560smod/} folder AP3 wrote its route cache and dumps into. */
    private static final String LEGACY_AP3_DIR = "killer560smod";

    /** Names this mod owns in the config root that do NOT start with {@link #PREFIX}. Only these (and prefixed names)
     *  are ever moved - no other mod's file is touched. */
    private static final Set<String> SPECIAL_NAMES = Set.of(
            "bug-reports",
            "proxyclient.json",
            "spotify-lyrics-lastfm.json",
            // AP3's route cache, route dumps and recorded trajectories lived in config/killer560smod/; its CONTENTS
            // are moved into dungeons/ap3 one by one (see migrateAll), the folder itself is not kept.
            LEGACY_AP3_DIR
    );

    /** Where anything with no entry in the table goes. */
    static final String OTHER = "other";

    /**
     * Name (with {@link #PREFIX} stripped) -> folder under {@link #root()}. Matched by PREFIX, longest key wins, so
     * {@code "run"} catches {@code runs}, {@code runstats} and {@code runsummary}, and a dynamic suffix such as
     * {@code autoroutes.broken-1696000000.json} or {@code bugreport-2026-10-04_12-00-00.zip} lands beside its file.
     * A name that matches nothing goes to {@code other/}.
     */
    static final Map<String, String> TABLE = buildTable();

    private static Map<String, String> buildTable() {
        Map<String, String> t = new LinkedHashMap<>();

        // ---- dungeons --------------------------------------------------------------------------------------------
        t.put("ap3", "dungeons/ap3");
        t.put(LEGACY_AP3_DIR, "dungeons/ap3");

        t.put("autoroutes", "dungeons/autoroutes");
        t.put("waypointroutes", "dungeons/autoroutes");
        t.put("etherwarp", "dungeons/autoroutes");

        t.put("autoclear", "dungeons/autoclear");
        // Auto Secret, and its insta-clear evidence (killer560smod-autosecret/insta-clear.json).
        t.put("autosecret", "dungeons/autosecret");
        // Auto Trap's trap routes (killer560smod-autotrap.json).
        t.put("autotrap", "dungeons/autotrap");

        t.put("breakeraura", "dungeons/breakeraura");
        t.put("dungeonbreaker", "dungeons/breakeraura");
        t.put("dungeonextras", "dungeons/breakeraura");

        for (String solver : new String[]{"beamssolver", "blazesolver", "bouldersolver", "icefillsolver",
                "icepathsolver", "quizsolver", "teleportmazesolver", "tictactoesolver", "watersolver",
                "weirdossolver", "autopuzzles", "solveresp"}) {
            t.put(solver, "dungeons/puzzles");
        }

        for (String terminal : new String[]{"terminal", "teammelody", "goldor", "i4", "p3nav", "simonsays",
                "leveraura", "arrowalign"}) {
            t.put(terminal, "dungeons/terminals");
        }

        for (String boss : new String[]{"maxor", "witherdragons", "thorn", "f7spots", "p4platformhighlight",
                "lividsolver", "bloodcamp"}) {
            t.put(boss, "dungeons/bosses");
        }

        for (String map : new String[]{"livemap", "mapping", "missingrooms", "floor-sizes", "mapdumps"}) {
            t.put(map, "dungeons/map");
        }

        for (String secret : new String[]{"secret", "door", "witherdoors"}) {
            t.put(secret, "dungeons/secrets");
        }

        for (String sim : new String[]{"sim-", "simmaps", "room", "measurements", "architectdraft"}) {
            t.put(sim, "dungeons/sim");
        }

        for (String run : new String[]{"run", "splittimers", "scorecalc", "dungeoninfo", "dungeonalerts",
                "dungeonqueue", "partyfinder", "leap", "fastleap", "spiritleapoverlay", "class", "teammates",
                "blessings", "croesus", "rng", "revertmasterstars", "ticktimers", "mobesp"}) {
            t.put(run, "dungeons/runs");
        }

        // ---- skyblock --------------------------------------------------------------------------------------------
        t.put("experiments", "skyblock/experiments");
        t.put("auction", "skyblock/auction");
        t.put("bazaarflip", "skyblock/bazaarflip");
        t.put("mining-profit", "skyblock/mining-profit");
        t.put("nucleus-profit", "skyblock/nucleus-profit");
        t.put("chmap", "skyblock/chmap");
        t.put("pathfinding", "skyblock/pathfinding");
        t.put("itemprotect", "skyblock/itemprotect");
        t.put("petwheel", "skyblock/petwheel");
        t.put("storage", "skyblock/storage");
        t.put("invsort", "skyblock/invsort");
        t.put("inventory", "skyblock/inventory");
        t.put("itembrowser", "skyblock/itembrowser");
        t.put("quiverdisplay", "skyblock/quiverdisplay");
        t.put("ragaxe", "skyblock/ragaxe");
        t.put("autosell", "skyblock/autosell");
        t.put("autoanvil", "skyblock/autoanvil");
        t.put("ability", "skyblock/abilities");
        t.put("slotbinds", "skyblock/slotbinds");
        t.put("loadoutkeybinds", "skyblock/loadoutkeybinds");
        t.put("maskinvincibility", "skyblock/maskinvincibility");
        t.put("autodebuff", "skyblock/autodebuff");
        t.put("autoclosechest", "skyblock/autoclosechest");
        t.put("cheatutils", "skyblock/cheatutils");
        t.put("autokick", "skyblock/autokick");
        t.put("autojoinskyblock", "skyblock/autojoinskyblock");

        // ---- social ----------------------------------------------------------------------------------------------
        t.put("friendslist", "social/friends");
        t.put("bestfriends", "social/friends");
        t.put("social", "social/friends");
        t.put("partycommands", "social/partycommands");
        t.put("partydata", "social/partycommands");
        t.put("chatcommands", "social/chatcommands");
        t.put("modchat", "social/modchat");
        t.put("playernames", "social/playernames");
        t.put("namechanger", "social/namechanger");
        t.put("supporters", "social/supporters");
        t.put("playerstats", "social/playerstats");
        t.put("profileviewer", "social/profileviewer");
        t.put("emotes", "social/emotes");
        t.put("automeow", "social/automeow");
        t.put("cringe", "social/cringe");
        t.put("translate", "social/translate");
        t.put("clicktranslate", "social/translate");
        t.put("autocorrect", "social/autocorrect");
        t.put("copychat", "social/copychat");
        t.put("chattidy", "social/chattidy");
        t.put("voice", "social/voicetotext");
        t.put("posmsg", "social/posmsg");
        t.put("bridge", "social/bridge");

        // ---- interface -------------------------------------------------------------------------------------------
        t.put("hud", "interface/hud");
        t.put("customscoreboard", "interface/customscoreboard");
        t.put("mainmenu", "interface/mainmenu");
        t.put("window", "interface/window");
        t.put("inventorytheme", "interface/inventorytheme");
        t.put("enchantcolors", "interface/enchantcolors");
        t.put("itemrarity", "interface/itemrarity");
        t.put("tooltipscroll", "interface/tooltipscroll");
        t.put("motionblur", "interface/motionblur");
        t.put("fullbright", "interface/fullbright");
        t.put("nofire", "interface/nofire");
        t.put("objecthider", "interface/objecthider");
        t.put("trail", "interface/trail");
        t.put("crosshair", "interface/crosshair");
        t.put("dvd", "interface/dvd");
        t.put("gif", "interface/gifplayer");
        t.put("screenshotcopy", "interface/screenshotcopy");
        t.put("realtime", "interface/realtime");
        t.put("lagdisplay", "interface/lagdisplay");
        t.put("trajectories", "interface/trajectories");
        t.put("helditem", "interface/helditem");
        t.put("position", "interface/position");
        t.put("armourdye", "interface/armourdye");
        t.put("diorite", "interface/dioriteglass");
        t.put("notify", "interface/notify");

        // ---- system ----------------------------------------------------------------------------------------------
        t.put("configversion", "system/configversion");
        t.put("sharing-defaults", "system/sharing-defaults");
        t.put("interop", "system/interop");
        t.put("updatecheck", "system/updatecheck");
        t.put("correctionalarm", "system/correctionalarm");
        t.put("bugreport", "system/bugreport");
        t.put("bug-reports", "system/bugreport");
        t.put("profiles", "system/profiles");
        t.put("active-profile", "system/profiles");
        t.put("session-login", "system/accounts");
        t.put("account-proxies", "system/accounts");
        t.put("proxyclient", "system/proxy");
        t.put("discordrpc", "system/discordrpc");
        t.put("spotify", "system/spotify");
        t.put("shorts", "system/shorts");
        t.put("skyblockonly", "system/skyblockonly");
        t.put("chunkcache", "system/chunkcache");
        t.put("relay", "system/relay");
        t.put("commandkeybinds", "system/commands");
        t.put("commandshortcuts", "system/commands");
        return t;
    }

    private static volatile Path root;
    private static volatile Path configDir;

    private ModPaths() {
    }

    /** Held apart so the mapping above can be exercised without a game or a logging backend. */
    private static final class Log {
        static final Logger LOGGER = ModLog.get("killer560smod-modpaths");
    }

    /** {@code config/} - Fabric's config folder. Only this class may use it for this mod's own files. */
    private static Path configDir() {
        Path dir = configDir;
        if (dir == null) {
            dir = FabricLoader.getInstance().getConfigDir();
            configDir = dir;
        }
        return dir;
    }

    /** {@code config/killer560/}. */
    public static Path root() {
        Path r = root;
        if (r == null) {
            r = configDir().resolve("killer560");
            root = r;
        }
        return r;
    }

    /** The folder (relative to {@link #root()}, forward slashes) that {@code legacyName} belongs in. A name may carry
     *  a leading folder ({@code "killer560smod/ap3-routes.json"}); the FIRST segment is what is looked up. */
    public static String folderFor(String legacyName) {
        String first = legacyName;
        int slash = first.indexOf('/');
        if (slash >= 0) {
            first = first.substring(0, slash);
        }
        String key = first.startsWith(PREFIX) ? first.substring(PREFIX.length()) : first;
        String best = null;
        int bestLength = -1;
        for (Map.Entry<String, String> e : TABLE.entrySet()) {
            String k = e.getKey();
            if (k.length() > bestLength && key.startsWith(k)) {
                best = e.getValue();
                bestLength = k.length();
            }
        }
        return best != null ? best : OTHER;
    }

    /** Where {@code legacyName} lives now, relative to {@link #root()}: its folder plus its own name. For a name with
     *  a leading folder ({@code "killer560smod/x.json"}) that old folder is dropped - only {@code x.json} is kept. */
    static String relativeFor(String legacyName) {
        String tail = legacyName;
        int slash = tail.indexOf('/');
        if (slash >= 0) {
            tail = tail.substring(slash + 1);
        }
        return folderFor(legacyName) + "/" + tail;
    }

    /**
     * The path of this mod's config file or folder {@code legacyName} - the name it has always had, e.g.
     * {@code "killer560smod-hud.json"} or {@code "killer560smod-ap3"}. Returns
     * {@code config/killer560/<category>/<feature>/<legacyName>}, with its parent folders created.
     *
     * <p>If the old {@code config/<legacyName>} still exists and the new one does not, it is moved first. Never
     * throws; if the move fails it logs a WARN and returns the OLD path, so a setting is never lost to a failed move.
     */
    public static Path config(String legacyName) {
        Path target = root().resolve(relativeFor(legacyName));
        try {
            if (Files.exists(target)) {
                return target;
            }
            Path legacy = configDir().resolve(legacyName);
            if (Files.exists(legacy)) {
                synchronized (ModPaths.class) {
                    if (!Files.exists(target) && Files.exists(legacy)) {
                        if (!move(legacy, target)) {
                            return legacy;
                        }
                    }
                }
            }
            Path parent = target.getParent();
            if (parent != null && !Files.isDirectory(parent)) {
                Files.createDirectories(parent);
            }
        } catch (Exception e) {
            Log.LOGGER.warn("[ModPaths] could not prepare {}", target, e);
        }
        return target;
    }

    /** Moves {@code legacy} to {@code target}, falling back to copy-then-delete (e.g. across volumes). Never throws.
     *  @return whether {@code target} now holds the data */
    private static boolean move(Path legacy, Path target) {
        try {
            Files.createDirectories(target.getParent());
            Files.move(legacy, target);
            return true;
        } catch (Exception moveFailed) {
            try {
                copyRecursive(legacy, target);
            } catch (Exception copyFailed) {
                Log.LOGGER.warn("[ModPaths] could not move {} to {} - still using the old location", legacy, target,
                        copyFailed);
                return false;
            }
            try {
                deleteRecursive(legacy);
            } catch (Exception deleteFailed) {
                // The copy is complete and is what gets used from now on; the leftover is only clutter.
                Log.LOGGER.warn("[ModPaths] copied {} to {} but could not delete the old copy", legacy, target,
                        deleteFailed);
            }
            return true;
        }
    }

    private static void copyRecursive(Path source, Path target) throws IOException {
        if (!Files.isDirectory(source)) {
            Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
            return;
        }
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(target.resolve(source.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, target.resolve(source.relativize(file).toString()),
                        StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteRecursive(Path path) throws IOException {
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

    /** Whether {@code name} in the config root is one of this mod's - the only entries {@link #migrateAll()} moves. */
    static boolean isOurs(String name) {
        return name.startsWith(PREFIX) || SPECIAL_NAMES.contains(name);
    }

    /**
     * Moves every one of this mod's files and folders out of the config root into the tree, so the root is clean
     * even for features never touched this session. Called first thing in the client entrypoint. Never throws.
     */
    public static void migrateAll() {
        List<String> names = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(configDir())) {
            for (Path entry : stream) {
                String name = entry.getFileName().toString();
                if (isOurs(name)) {
                    names.add(name);
                }
            }
        } catch (Exception e) {
            Log.LOGGER.warn("[ModPaths] could not list the config folder", e);
            return;
        }
        int moved = 0;
        for (String name : names) {
            try {
                if (name.equals(LEGACY_AP3_DIR)) {
                    moved += migrateLegacyAp3Dir();
                    continue;
                }
                Path legacy = configDir().resolve(name);
                if (!config(name).equals(legacy) && !Files.exists(legacy)) {
                    moved++;
                }
            } catch (Exception e) {
                Log.LOGGER.warn("[ModPaths] could not migrate {}", name, e);
            }
        }
        if (moved > 0) {
            Log.LOGGER.info("[ModPaths] moved {} config file(s)/folder(s) into {}", moved, root());
        }
    }

    /** {@code config/killer560smod/<file>} -> {@code dungeons/ap3/<file>}, then the emptied folder is removed. */
    private static int migrateLegacyAp3Dir() throws IOException {
        Path dir = configDir().resolve(LEGACY_AP3_DIR);
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        List<String> children = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path child : stream) {
                children.add(child.getFileName().toString());
            }
        }
        int moved = 0;
        for (String child : children) {
            Path legacy = dir.resolve(child);
            if (!config(LEGACY_AP3_DIR + "/" + child).equals(legacy) && !Files.exists(legacy)) {
                moved++;
            }
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            if (!stream.iterator().hasNext()) {
                Files.delete(dir);
            }
        }
        return moved;
    }

    /**
     * Every live {@code killer560smod-*.json} setting file - what used to be {@code newDirectoryStream(configDir,
     * "killer560smod-*.json")}. Only files sitting directly in a feature folder count (the same depth the config root
     * used to be), so a profile's own copies under {@code system/profiles/killer560smod-profiles/<name>/} and any
     * feature's sub-folder data are never mistaken for live settings. A file the migration could not move, still in
     * the config root, is included too unless the tree already has one of that name.
     */
    public static List<Path> settingFiles() {
        Map<String, Path> byName = new LinkedHashMap<>();
        Path r = root();
        if (Files.isDirectory(r)) {
            try (var walk = Files.walk(r, 3)) {
                walk.filter(p -> r.relativize(p).getNameCount() == 3)
                        .filter(Files::isRegularFile)
                        .filter(p -> isSettingName(p.getFileName().toString()))
                        .sorted()
                        .forEach(p -> byName.putIfAbsent(p.getFileName().toString(), p));
            } catch (Exception e) {
                Log.LOGGER.warn("[ModPaths] could not list {}", r, e);
            }
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(configDir(), PREFIX + "*.json")) {
            for (Path entry : stream) {
                if (Files.isRegularFile(entry)) {
                    byName.putIfAbsent(entry.getFileName().toString(), entry);
                }
            }
        } catch (Exception ignored) {
            // No config folder yet - nothing left over to include.
        }
        return new ArrayList<>(byName.values());
    }

    private static boolean isSettingName(String name) {
        return name.startsWith(PREFIX) && name.endsWith(".json");
    }
}
