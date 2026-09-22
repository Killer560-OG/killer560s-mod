package com.killer560.hub.ap3;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.dungeonclass.DungeonClass;
import com.killer560.hub.fastleap.Floor7Tracker.Phase;
import com.killer560.hub.util.ConfigJson;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.Direction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * All AP3 chains, in ONE shareable file - the same rule as Auto Routes (killer560: "I am fine with it being a json
 * file as long as it is easy to edit in notepad and share really easily. I should only have to share one file.").
 * Settings live in their own file ({@link Ap3Config}), so this one is nothing but chains.
 * <p>
 * <b>Which file</b> (killer560, 2026-09-21: "I should be able to click into it and select any of the ap3's in my
 * folder, and create new ones"): every chains file lives in {@code config/killer560smod-ap3/} and exactly one of them
 * is in use at a time - {@link Ap3Config#getChainsFile()}, {@code default.json} until he picks another. The old
 * single file {@code config/killer560smod-ap3.json} is copied into the folder as {@code default.json} the first time
 * the folder has no such file, and left where it was. {@link #listConfigNames()}, {@link #select} and
 * {@link #createConfig} are what the "Choose AP3 Config" screen drives.
 * <p>
 * Layout, chosen so a person can find a section, nudge a node and {@code /ap3 reload}:
 * <pre>
 * { "version": 2,
 *   "chains": {
 *     "S1":      { "phase": "P3", "section": 1, "class": "",     "nodes": [ { "type": "ALIGN", "x": 100.5, "y": 110.0, "z": 60.5, "yaw": -90.0, "pitch": 0.0, "width": 3.0, "length": 3.0 }, ... ] },
 *     "S1:MAGE": { "phase": "P3", "section": 1, "class": "MAGE", "nodes": [ ... ] },
 *     "P1":      { "phase": "P1", "section": 0, "class": "",     "nodes": [ ... ] }
 *   } }
 * </pre>
 * {@code "phase"} arrived with the any-boss-phase change (2026-09-20); a file without it is a P3-only file from before
 * and every {@code "S<n>"} chain in it loads as P3 section n exactly as it always did.
 * <p>
 * <b>Version 1 -> 2 (the 2026-09-20 node rework)</b>, applied on load so a chain he already recorded keeps working:
 * {@code LINE} loads as {@code ALIGN}, {@code AXIS_LINE} as {@code AXIS_ALIGN} (its FRONT/LEFT/RIGHT wall turned into
 * the world side nearest that direction), {@code LEAP_DETECTOR} as {@code LEAP_COUNTER}; a {@code WAIT} node becomes
 * the {@code wait:} modifier of the node before it (a leading WAIT becomes a STOP carrying the wait); a
 * {@code BREAKER} node is dropped (Breaker Aura does that job now). Every migration is logged and reported once in
 * chat ({@link #consumeMigrationNotes}), and the file is rewritten in the new form the next time anything saves.
 * <p>
 * Loading is defensive because this file is meant to be handed around: chain/node counts and string lengths are
 * capped, NaN/infinite/absurd coordinates are dropped, a malformed node is skipped (the rest of the chain loads),
 * and a file whose parse fails is copied aside ONCE as {@code killer560smod-ap3.broken.json} and never saved over -
 * the same rule {@code autoroutes/RouteStore} follows after its 2026-09-16 review.
 */
public final class Ap3Store {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-ap3");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    /** The folder every chains file lives in, inside the config dir. */
    private static final String FOLDER_NAME = "killer560smod-ap3";
    /** Where the one file used to be (config dir root); migrated into the folder, never written again. */
    private static final String LEGACY_FILE_NAME = "killer560smod-ap3.json";
    /** The chains file in use until one is chosen. */
    public static final String DEFAULT_CONFIG_NAME = "default.json";
    private static final String JSON = ".json";
    /** A file that failed to parse is copied aside as {@code <name>.broken.json}; those never show in the chooser. */
    private static final String BROKEN_SUFFIX = ".broken.json";
    /** Letters, digits, space, dash, underscore, dot - a name Windows, Discord and Notepad all agree on. */
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9 _\\-.]+");
    public static final int MAX_CONFIG_NAME = 40;
    private static final int FORMAT_VERSION = 2;

    // Proportionate caps for a friend's file, not a network input.
    /** 9 areas (P1, P2, S1-S5, P4, P5) x (1 class-less + 5 classes) = 54 possible chains; headroom for hand edits. */
    public static final int MAX_CHAINS = 64;
    public static final int MAX_NODES = 200;
    public static final int MAX_IGN = 16;
    /** The boss arena is well inside +-300; anything past this is garbage. */
    public static final double MAX_ABS_COORD = 1024.0;

    private static Ap3Store instance;

    private final Map<String, Ap3Chain> chains = new LinkedHashMap<>();
    /** Set when the last load could not parse the file: {@link #save()} refuses to overwrite it until a load succeeds
     *  or the user explicitly makes a change (which starts from the recovered/empty state they can see). */
    private boolean parseFailed;
    /** One line per node the version-1 migration changed or dropped; shown once in chat, then cleared. */
    private final List<String> migrationNotes = new ArrayList<>();

    private Ap3Store() {
    }

    public static Ap3Store getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    /** The folder every chains file lives in (the tab's "Open Folder" button); created if missing. */
    public static Path directory() {
        Path dir = FabricLoader.getInstance().getConfigDir().resolve(FOLDER_NAME);
        try {
            Files.createDirectories(dir);
        } catch (Exception ignored) {
        }
        return dir;
    }

    /** The chains file in use: {@link Ap3Config#getChainsFile()} inside {@link #directory()}. */
    public static Path file() {
        return directory().resolve(Ap3Config.getInstance().getChainsFile());
    }

    /** {@code /ap3 reload}: re-read the file, replacing what is in memory. Stops a running chain first rather than
     *  swapping the node list out from under the executor. Safe from {@code ProfileManager.reloadAllConfigs}. */
    public static void reload() {
        if (Ap3Executor.isRunning()) {
            Ap3Executor.stop("chains reloaded");
        }
        load();
        Ap3Feature.onChainsReloaded();
    }

    // ------------------------------------------------------------------------------------------- choosing a file

    /** Every chains file in the folder by name ({@code *.json}, the broken backups left out), sorted, and always
     *  including the one in use even when it is not on disk yet (it is written on the first save). */
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
            LOGGER.warn("[AP3] Could not list {}", directory(), e);
        }
        String active = Ap3Config.getInstance().getChainsFile();
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
     * Why {@code name} (already {@link #normalizeConfigName normalized}) cannot be a chains file name, or null when
     * it can: a plain file name only - no separators, no "..", no leading dot, safe characters, a sane length. This
     * is the only rule between a typed name and a path under the config folder.
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
     * Creates an EMPTY chains file called {@code rawName} and switches to it. Refuses (with the reason) a bad name or
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
            LOGGER.warn("[AP3] Could not create {}", file, e);
            return "Could not write the file (see the log).";
        }
        LOGGER.info("[AP3] Created chains file {}", name);
        select(name);
        return null;
    }

    /** Switches to the chains file {@code name} (a name from {@link #listConfigNames()}): persisted, then loaded
     *  exactly as {@code /ap3 reload} would. A running chain is stopped first. */
    public static void select(String name) {
        String clean = normalizeConfigName(name);
        if (clean == null || validateConfigName(clean) != null) {
            return;
        }
        Ap3Config cfg = Ap3Config.getInstance();
        if (clean.equalsIgnoreCase(cfg.getChainsFile())) {
            return;
        }
        if (Ap3Executor.isRunning()) {
            Ap3Executor.stop("chains file changed");
        }
        cfg.setChainsFile(clean);
        cfg.save();
        LOGGER.info("[AP3] Chains file: {}", clean);
        load();
        Ap3Feature.onChainsReloaded();
    }

    /** The skeleton every chains file starts from - what a fresh install would write on its first save. */
    private static JsonObject emptyRoot() {
        JsonObject root = new JsonObject();
        root.addProperty("version", FORMAT_VERSION);
        root.addProperty("note", "AP3 - one chain per boss area (P1, P2, P3 sections S1-S5, P4, P5), optionally per class, absolute coordinates. "
                + "Node types: ALIGN, AXIS_ALIGN, WALK, RUN, LEAP, LEAP_COUNTER, TERMINAL, STOP, LOOK, BOOM, STOPWATCH, JUMP, EDGE, BLOCK. "
                + "Every node has a trigger box (width x length), and the modifiers waitAfterMs / close. Edit, then /ap3 reload.");
        root.add("chains", new JsonObject());
        return root;
    }

    /** The pre-folder file, copied in as {@code default.json} once so nothing he recorded is lost by the move. The
     *  original stays put (it is his to delete) and is never read again once the copy exists. */
    private static void migrateLegacyFile() {
        try {
            Path target = directory().resolve(DEFAULT_CONFIG_NAME);
            Path legacy = FabricLoader.getInstance().getConfigDir().resolve(LEGACY_FILE_NAME);
            if (!Files.exists(target) && Files.isRegularFile(legacy)) {
                Files.copy(legacy, target);
                LOGGER.info("[AP3] Copied the old {} into {}/{}", LEGACY_FILE_NAME, FOLDER_NAME, DEFAULT_CONFIG_NAME);
            }
        } catch (Exception e) {
            LOGGER.warn("[AP3] Could not migrate the old chains file", e);
        }
    }

    public static void load() {
        migrateLegacyFile();
        Ap3Store store = new Ap3Store();
        Path file = file();
        String fileName = file.getFileName().toString();
        if (Files.exists(file)) {
            try {
                JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
                int version = ConfigJson.getInt(root, "version", 1);
                JsonObject chainsObj = ConfigJson.getObject(root, "chains");
                if (chainsObj != null) {
                    for (String key : chainsObj.keySet()) {
                        if (store.chains.size() >= MAX_CHAINS) {
                            LOGGER.warn("[AP3] More than {} chains in {} - the rest were ignored", MAX_CHAINS, fileName);
                            break;
                        }
                        JsonObject chainObj = ConfigJson.getObject(chainsObj, key);
                        if (chainObj == null) {
                            continue;
                        }
                        try {
                            Ap3Chain chain = store.readChain(key, chainObj, version);
                            if (chain != null && !chain.isEmpty()) {
                                store.chains.put(chain.key(), chain);
                            }
                        } catch (Exception e) {
                            LOGGER.warn("[AP3] Skipping unreadable chain \"{}\": {}", key, e.toString());
                        }
                    }
                }
                LOGGER.info("[AP3] Loaded {} chain(s) from {} (file version {})", store.chains.size(), fileName, version);
                for (String note : store.migrationNotes) {
                    LOGGER.warn("[AP3] Migration: {}", note);
                }
            } catch (Exception e) {
                store.parseFailed = true;
                store.chains.clear();
                LOGGER.warn("[AP3] Failed to parse {} - backing it up, nothing will be saved over it", fileName, e);
                try {
                    // One backup per broken file, not one per reload: /ap3 reload and every profile switch come
                    // back through here, and the original is already safe after the first copy.
                    Path backup = file.resolveSibling(brokenName(fileName));
                    if (!Files.exists(backup)) {
                        Files.copy(file, backup);
                    }
                } catch (Exception backupError) {
                    LOGGER.warn("[AP3] Could not back up the unreadable chains file", backupError);
                }
            }
        }
        instance = store;
    }

    /** Writes every chain. Refuses while the file on disk failed to parse and nothing has been changed since, so a
     *  half-downloaded file from a friend can't be replaced by an empty one behind the user's back. */
    public void save() {
        Path file = file();
        String fileName = file.getFileName().toString();
        if (parseFailed) {
            LOGGER.warn("[AP3] Not saving {}: the file on disk could not be parsed (see {})", fileName, brokenName(fileName));
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            JsonObject root = emptyRoot();
            JsonObject chainsObj = new JsonObject();
            for (Ap3Chain chain : chains.values()) {
                chainsObj.add(chain.key(), writeChain(chain));
            }
            root.add("chains", chainsObj);
            Files.writeString(file, GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.warn("[AP3] Failed to save {}", fileName, e);
        }
    }

    /** {@code default.json} -> {@code default.broken.json}. */
    private static String brokenName(String fileName) {
        String stem = fileName.toLowerCase(Locale.ROOT).endsWith(JSON)
                ? fileName.substring(0, fileName.length() - JSON.length()) : fileName;
        return stem + BROKEN_SUFFIX;
    }

    // ------------------------------------------------------------------------------------------- access

    /** Every chain, in file order (unmodifiable). Mutate through {@link #forAreaOrCreate}/{@link #remove}. */
    public List<Ap3Chain> chains() {
        return Collections.unmodifiableList(new ArrayList<>(chains.values()));
    }

    /** The class-less chain for an area (the one that runs for any class), or null when none is saved. Same
     *  shape as {@code RouteStore.forRoom}. */
    public Ap3Chain forArea(Ap3Area area) {
        return area == null ? null : chains.get(Ap3Chain.key(area, null));
    }

    /** The chain that should run in {@code area} when playing {@code playing}: the chain filtered to that class
     *  when one exists, else the class-less chain, else null. */
    public Ap3Chain forArea(Ap3Area area, DungeonClass playing) {
        if (area == null) {
            return null;
        }
        Ap3Chain specific = playing == null ? null : chains.get(Ap3Chain.key(area, playing));
        return specific != null ? specific : forArea(area);
    }

    /** Exactly the chain keyed (area, classFilter), or null. */
    public Ap3Chain exact(Ap3Area area, DungeonClass classFilter) {
        return area == null ? null : chains.get(Ap3Chain.key(area, classFilter));
    }

    /** Every chain for an area (class-less first, then per class), for the tab. */
    public List<Ap3Chain> chainsFor(Ap3Area area) {
        List<Ap3Chain> out = new ArrayList<>();
        for (Ap3Chain c : chains.values()) {
            if (c.area().equals(area)) {
                out.add(c);
            }
        }
        out.sort((a, b) -> {
            if (a.classFilter() == null) {
                return b.classFilter() == null ? 0 : -1;
            }
            return b.classFilter() == null ? 1 : a.classFilter().compareTo(b.classFilter());
        });
        return out;
    }

    /** The chain keyed (area, classFilter), created empty if missing (not saved until {@link #save()}). */
    public Ap3Chain forAreaOrCreate(Ap3Area area, DungeonClass classFilter) {
        String key = Ap3Chain.key(area, classFilter);
        Ap3Chain chain = chains.get(key);
        if (chain == null) {
            chain = new Ap3Chain(area, classFilter);
            chains.put(key, chain);
            parseFailed = false; // an explicit new chain is the user's own decision to start over
        }
        return chain;
    }

    public boolean remove(Ap3Chain chain) {
        boolean removed = chain != null && chains.remove(chain.key()) != null;
        if (removed) {
            parseFailed = false;
        }
        return removed;
    }

    /** Called after an explicit user edit so a recovered/empty store may be saved again. */
    public void markEdited() {
        parseFailed = false;
    }

    public boolean lastLoadFailed() {
        return parseFailed;
    }

    /** The version-1 migration report, once: what was renamed, merged or dropped. Empty when nothing was. */
    public List<String> consumeMigrationNotes() {
        if (migrationNotes.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<>(migrationNotes);
        migrationNotes.clear();
        return out;
    }

    // ------------------------------------------------------------------------------------------- codec

    private Ap3Chain readChain(String key, JsonObject obj, int version) {
        // "phase"/"section"/"class" in the object win over the key (a hand-edited key that disagrees is a typo); the
        // key is the fallback so a file with only keys still loads. No "phase" at all = a file from before AP3 ran
        // outside P3: its chains are P3 sections, which is what "section" alone always meant.
        Ap3Area fromKey = Ap3Area.parseKey(key);
        String phaseName = ConfigJson.getString(obj, "phase", null);
        Phase phase;
        if (phaseName != null && !phaseName.isBlank()) {
            try {
                phase = Phase.valueOf(phaseName.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return null;
            }
        } else {
            phase = fromKey != null ? fromKey.phase() : Phase.P3;
        }
        int section = ConfigJson.getInt(obj, "section", fromKey != null ? fromKey.section() : 0);
        if (phase != Phase.P3) {
            section = 0; // a stray "section" on a P1/P2/P4/P5 chain is meaningless, not a reason to drop the chain
        }
        Ap3Area area = Ap3Area.of(phase, section);
        if (area == null) {
            return null;
        }
        String cls = ConfigJson.getString(obj, "class", null);
        DungeonClass classFilter = cls == null ? Ap3Chain.parseClassFilter(key)
                : (cls.isBlank() ? null : DungeonClass.byName(cls));
        Ap3Chain chain = new Ap3Chain(area, classFilter);
        JsonArray nodes = ConfigJson.getArray(obj, "nodes");
        if (nodes != null) {
            int position = 0;
            for (JsonElement el : nodes) {
                if (chain.nodes().size() >= MAX_NODES) {
                    LOGGER.warn("[AP3] Chain {} has more than {} nodes - the rest were ignored", chain.key(), MAX_NODES);
                    break;
                }
                if (el == null || !el.isJsonObject()) {
                    continue;
                }
                position++;
                try {
                    JsonObject o = el.getAsJsonObject();
                    String rawType = ConfigJson.getString(o, "type", "");
                    String typeKey = rawType.trim().toLowerCase(Locale.ROOT).replace('-', '_');
                    if (typeKey.equals("wait") || typeKey.equals("delay")) {
                        // Version 1 WAIT node -> the wait: modifier of the node before it (killer560: "modifiers,
                        // replacing the wait node"). Nothing to attach a leading wait to, so it becomes a STOP
                        // that carries it - the chain still pauses where it used to.
                        int ms = Math.max(0, ConfigJson.getInt(o, "waitMs", 1000));
                        List<Ap3Node> list = chain.nodes();
                        if (!list.isEmpty()) {
                            Ap3Node prev = list.get(list.size() - 1);
                            prev.setWaitAfterMs(prev.waitAfterMs + ms);
                            migrationNotes.add(chain.label() + ": Wait node #" + position + " (" + ms + " ms) is now wait:"
                                    + prev.waitAfterMs + " on " + prev.type.label() + " #" + list.size());
                        } else {
                            Ap3Node stop = readNode(o, version);
                            if (stop != null) {
                                stop.type = Ap3Node.Type.STOP;
                                stop.setWaitAfterMs(ms);
                                list.add(stop);
                                migrationNotes.add(chain.label() + ": leading Wait node #" + position + " is now a Stop with wait:" + ms);
                            }
                        }
                        continue;
                    }
                    if (typeKey.equals("breaker") || typeKey.equals("db") || typeKey.equals("dungeonbreaker") || typeKey.equals("dungeon_breaker")) {
                        migrationNotes.add(chain.label() + ": Breaker node #" + position + " dropped - Breaker Aura covers it now");
                        continue;
                    }
                    Ap3Node node = readNode(o, version);
                    if (node != null) {
                        if (version < 2 && !typeKey.equals(node.type.name().toLowerCase(Locale.ROOT))) {
                            migrationNotes.add(chain.label() + ": #" + position + " " + rawType + " loaded as " + node.type.label());
                        }
                        chain.nodes().add(node);
                    }
                } catch (Exception e) {
                    // skip just this node; the rest of the chain still loads
                }
            }
        }
        return chain;
    }

    private static Ap3Node readNode(JsonObject o, int version) {
        Ap3Node.Type type = Ap3Node.Type.parse(ConfigJson.getString(o, "type", null));
        if (type == null) {
            return null;
        }
        double x = ConfigJson.getDouble(o, "x", Double.NaN);
        double y = ConfigJson.getDouble(o, "y", Double.NaN);
        double z = ConfigJson.getDouble(o, "z", Double.NaN);
        if (!finiteCoord(x) || !finiteCoord(y) || !finiteCoord(z)) {
            return null;
        }
        // Coordinates are kept EXACTLY as written: a precise align's x.7 is the whole point of it, and a version-1
        // file's half-block positions were already snapped when they were placed.
        Ap3Node n = new Ap3Node(type, x, Ap3Node.snapY(y), z,
                ConfigJson.getFloat(o, "yaw", 0f),
                Math.max(-90f, Math.min(90f, ConfigJson.getFloat(o, "pitch", 0f))));
        n.setLength(ConfigJson.getDouble(o, "length", n.length));
        n.setWidth(ConfigJson.getDouble(o, "width", n.width));
        n.precise = ConfigJson.getBool(o, "precise", false);
        n.setWaitAfterMs(ConfigJson.getInt(o, "waitAfterMs", 0));
        n.closeGate = ConfigJson.getBool(o, "close", false);
        n.jumpMod = ConfigJson.getEnum(o, "jump", Ap3Node.JumpMod.class, Ap3Node.JumpMod.NONE);
        if (o.has("name") && o.get("name").isJsonPrimitive()) {
            String nm = o.get("name").getAsString().trim();
            n.name = nm.isEmpty() ? null : nm.substring(0, Math.min(16, nm.length()));
        }
        n.leapMode = ConfigJson.getEnum(o, "leapMode", Ap3Node.LeapMode.class, Ap3Node.LeapMode.DEFAULT);
        n.leapClass = DungeonClass.byName(ConfigJson.getString(o, "leapClass", null));
        n.leapIgn = cleanString(ConfigJson.getString(o, "leapIgn", null), MAX_IGN);
        n.setLeapCount(ConfigJson.getInt(o, "leapCount", 1));
        if (type == Ap3Node.Type.AXIS_ALIGN) {
            n.wallDir = parseWall(ConfigJson.getString(o, "wall", null));
            if (n.wallDir == null) {
                // Version 1 stored the wall relative to the node's yaw (FRONT / LEFT / RIGHT); the nearest world
                // side to that direction is what the new node needs.
                String axis = ConfigJson.getString(o, "wallAxis", "NONE").trim().toUpperCase(Locale.ROOT);
                Direction front = Ap3Node.nearestHorizontal(n.yaw);
                n.wallDir = switch (axis) {
                    case "FRONT" -> front;
                    case "BACK" -> front.getOpposite();
                    case "LEFT" -> front.getCounterClockWise();
                    case "RIGHT" -> front.getClockWise();
                    default -> null;
                };
            }
        }
        String colour = ConfigJson.getString(o, "colour", null);
        if (colour != null) {
            n.colour = parseColour(colour);
        }
        return n;
    }

    private static JsonObject writeChain(Ap3Chain chain) {
        JsonObject obj = new JsonObject();
        obj.addProperty("phase", chain.phase().name());
        obj.addProperty("section", chain.section());
        obj.addProperty("class", chain.classFilter() == null ? "" : chain.classFilter().name());
        JsonArray nodes = new JsonArray();
        for (Ap3Node n : chain.nodes()) {
            nodes.add(writeNode(n));
        }
        obj.add("nodes", nodes);
        return obj;
    }

    private static JsonObject writeNode(Ap3Node n) {
        JsonObject o = new JsonObject();
        o.addProperty("type", n.type.name());
        o.addProperty("x", round(n.x, 4));
        o.addProperty("y", round(n.y, 3));
        o.addProperty("z", round(n.z, 4));
        o.addProperty("yaw", round(n.yaw, 1));
        o.addProperty("pitch", round(n.pitch, 1));
        o.addProperty("width", round(n.width, 2));
        o.addProperty("length", round(n.length, 2));
        if (n.precise) {
            o.addProperty("precise", true);
        }
        if (n.waitAfterMs > 0) {
            o.addProperty("waitAfterMs", n.waitAfterMs);
        }
        if (n.closeGate) {
            o.addProperty("close", true);
        }
        if (n.jumpMod != Ap3Node.JumpMod.NONE) {
            o.addProperty("jump", n.jumpMod.name());
        }
        if (n.name != null) {
            o.addProperty("name", n.name);
        }
        switch (n.type) {
            case AXIS_ALIGN -> o.addProperty("wall", n.wallDir == null ? "" : n.wallDir.getName());
            case LEAP -> {
                o.addProperty("leapMode", n.leapMode.name());
                o.addProperty("leapClass", n.leapClass == null ? "" : n.leapClass.name());
                o.addProperty("leapIgn", n.leapIgn == null ? "" : n.leapIgn);
            }
            case LEAP_COUNTER -> o.addProperty("leapCount", n.leapCount);
            default -> {
            }
        }
        if (n.colour != null) {
            o.addProperty("colour", String.format(Locale.ROOT, "#%08X", n.colour));
        }
        return o;
    }

    // ------------------------------------------------------------------------------------------- helpers

    private static boolean finiteCoord(double v) {
        return Double.isFinite(v) && Math.abs(v) <= MAX_ABS_COORD;
    }

    private static double round(double v, int decimals) {
        double f = Math.pow(10, decimals);
        return Math.round(v * f) / f;
    }

    static String cleanString(String s, int max) {
        if (s == null) {
            return null;
        }
        String t = s.strip().replaceAll("[\\p{Cntrl}]", "");
        if (t.isEmpty()) {
            return null;
        }
        return t.length() > max ? t.substring(0, max) : t;
    }

    /** "north" / "EAST" / ... -> a horizontal direction, else null. */
    private static Direction parseWall(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        Direction d = Direction.byName(s.trim().toLowerCase(Locale.ROOT));
        return d != null && d.getAxis().isHorizontal() ? d : null;
    }

    private static Integer parseColour(String s) {
        try {
            String t = s.trim();
            if (t.startsWith("#")) {
                t = t.substring(1);
            }
            long v = Long.parseLong(t, 16);
            if (t.length() <= 6) {
                v |= 0xFF000000L;
            }
            return (int) v;
        } catch (Exception e) {
            return null;
        }
    }
}
