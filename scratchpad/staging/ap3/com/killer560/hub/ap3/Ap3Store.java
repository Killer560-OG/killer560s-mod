package com.killer560.hub.ap3;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.dungeonclass.DungeonClass;
import com.killer560.hub.util.ConfigJson;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
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

/**
 * All AP3 chains, in ONE shareable file: {@code config/killer560smod-ap3.json} - the same rule as Auto Routes
 * (killer560: "I am fine with it being a json file as long as it is easy to edit in notepad and share really easily.
 * I should only have to share one file."). Settings live in their own file ({@link Ap3Config}), so this one is
 * nothing but chains.
 * <p>
 * Layout, chosen so a person can find a section, nudge a node and {@code /ap3 reload}:
 * <pre>
 * { "version": 1,
 *   "chains": {
 *     "S1":      { "section": 1, "class": "",     "nodes": [ { "type": "LINE", "x": 100.5, "y": 110.0, "z": 60.0, "yaw": -90.0, "pitch": 0.0, "length": 3.0, "width": 1.0 }, ... ] },
 *     "S1:MAGE": { "section": 1, "class": "MAGE", "nodes": [ ... ] }
 *   } }
 * </pre>
 * Loading is defensive because this file is meant to be handed around: chain/node/block counts and string lengths
 * are capped, NaN/infinite/absurd coordinates are dropped, a malformed node is skipped (the rest of the chain
 * loads), and a file whose parse fails is copied aside ONCE as {@code killer560smod-ap3.broken.json} and never
 * saved over - the same rule {@code autoroutes/RouteStore} follows after its 2026-09-16 review.
 */
public final class Ap3Store {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-ap3");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "killer560smod-ap3.json";
    private static final String BROKEN_NAME = "killer560smod-ap3.broken.json";
    private static final int FORMAT_VERSION = 1;

    // Proportionate caps for a friend's file, not a network input.
    /** 5 sections x (1 class-less + 5 classes) = 30 possible chains; a little headroom for hand edits. */
    public static final int MAX_CHAINS = 40;
    public static final int MAX_NODES = 200;
    public static final int MAX_BREAKER_BLOCKS = 20;
    public static final int MAX_IGN = 16;
    /** The boss arena is well inside +-300; anything past this is garbage. */
    public static final double MAX_ABS_COORD = 1024.0;

    private static Ap3Store instance;

    private final Map<String, Ap3Chain> chains = new LinkedHashMap<>();
    /** Set when the last load could not parse the file: {@link #save()} refuses to overwrite it until a load succeeds
     *  or the user explicitly makes a change (which starts from the recovered/empty state they can see). */
    private boolean parseFailed;

    private Ap3Store() {
    }

    public static Ap3Store getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    /** Folder containing the chains file (for the tab's "Open Folder" button); created if missing. */
    public static Path directory() {
        Path dir = FabricLoader.getInstance().getConfigDir();
        try {
            Files.createDirectories(dir);
        } catch (Exception ignored) {
        }
        return dir;
    }

    public static Path file() {
        return directory().resolve(FILE_NAME);
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

    public static void load() {
        Ap3Store store = new Ap3Store();
        Path file = file();
        if (Files.exists(file)) {
            try {
                JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
                JsonObject chainsObj = ConfigJson.getObject(root, "chains");
                if (chainsObj != null) {
                    for (String key : chainsObj.keySet()) {
                        if (store.chains.size() >= MAX_CHAINS) {
                            LOGGER.warn("[AP3] More than {} chains in {} - the rest were ignored", MAX_CHAINS, FILE_NAME);
                            break;
                        }
                        JsonObject chainObj = ConfigJson.getObject(chainsObj, key);
                        if (chainObj == null) {
                            continue;
                        }
                        try {
                            Ap3Chain chain = readChain(key, chainObj);
                            if (chain != null && !chain.isEmpty()) {
                                store.chains.put(chain.key(), chain);
                            }
                        } catch (Exception e) {
                            LOGGER.warn("[AP3] Skipping unreadable chain \"{}\": {}", key, e.toString());
                        }
                    }
                }
                LOGGER.info("[AP3] Loaded {} chain(s) from {}", store.chains.size(), FILE_NAME);
            } catch (Exception e) {
                store.parseFailed = true;
                store.chains.clear();
                LOGGER.warn("[AP3] Failed to parse {} - backing it up, nothing will be saved over it", FILE_NAME, e);
                try {
                    // One backup per broken file, not one per reload: /ap3 reload and every profile switch come
                    // back through here, and the original is already safe after the first copy.
                    Path backup = file.resolveSibling(BROKEN_NAME);
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
        if (parseFailed) {
            LOGGER.warn("[AP3] Not saving {}: the file on disk could not be parsed (see {})", FILE_NAME, BROKEN_NAME);
            return;
        }
        try {
            Path file = file();
            Files.createDirectories(file.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("version", FORMAT_VERSION);
            root.addProperty("note", "AP3 - one chain per P3 section (S1-S5), optionally per class, absolute coordinates. Edit the nodes, then /ap3 reload.");
            JsonObject chainsObj = new JsonObject();
            for (Ap3Chain chain : chains.values()) {
                chainsObj.add(chain.key(), writeChain(chain));
            }
            root.add("chains", chainsObj);
            Files.writeString(file, GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.warn("[AP3] Failed to save {}", FILE_NAME, e);
        }
    }

    // ------------------------------------------------------------------------------------------- access

    /** Every chain, in file order (unmodifiable). Mutate through {@link #forSectionOrCreate}/{@link #remove}. */
    public List<Ap3Chain> chains() {
        return Collections.unmodifiableList(new ArrayList<>(chains.values()));
    }

    /** The class-less chain for a section (the one that runs for any class), or null when none is saved. Same
     *  shape as {@code RouteStore.forRoom}. */
    public Ap3Chain forSection(int section) {
        return chains.get(Ap3Chain.key(section, null));
    }

    /** The chain that should run for {@code section} when playing {@code playing}: the chain filtered to that class
     *  when one exists, else the class-less chain, else null. */
    public Ap3Chain forSection(int section, DungeonClass playing) {
        Ap3Chain specific = playing == null ? null : chains.get(Ap3Chain.key(section, playing));
        return specific != null ? specific : forSection(section);
    }

    /** Exactly the chain keyed (section, classFilter), or null. */
    public Ap3Chain exact(int section, DungeonClass classFilter) {
        return chains.get(Ap3Chain.key(section, classFilter));
    }

    /** Every chain for a section (class-less first, then per class), for the tab. */
    public List<Ap3Chain> chainsFor(int section) {
        List<Ap3Chain> out = new ArrayList<>();
        for (Ap3Chain c : chains.values()) {
            if (c.section() == section) {
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

    /** The chain keyed (section, classFilter), created empty if missing (not saved until {@link #save()}). */
    public Ap3Chain forSectionOrCreate(int section, DungeonClass classFilter) {
        String key = Ap3Chain.key(section, classFilter);
        Ap3Chain chain = chains.get(key);
        if (chain == null) {
            chain = new Ap3Chain(section, classFilter);
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

    // ------------------------------------------------------------------------------------------- codec

    private static Ap3Chain readChain(String key, JsonObject obj) {
        // "section"/"class" in the object win over the key (a hand-edited key that disagrees is a typo); the key is
        // the fallback so a file with only keys still loads.
        int section = ConfigJson.getInt(obj, "section", Ap3Chain.parseSection(key));
        if (section < 1 || section > 5) {
            return null;
        }
        String cls = ConfigJson.getString(obj, "class", null);
        DungeonClass classFilter = cls == null ? Ap3Chain.parseClassFilter(key)
                : (cls.isBlank() ? null : DungeonClass.byName(cls));
        Ap3Chain chain = new Ap3Chain(section, classFilter);
        JsonArray nodes = ConfigJson.getArray(obj, "nodes");
        if (nodes != null) {
            for (JsonElement el : nodes) {
                if (chain.nodes().size() >= MAX_NODES) {
                    LOGGER.warn("[AP3] Chain {} has more than {} nodes - the rest were ignored", chain.key(), MAX_NODES);
                    break;
                }
                if (el == null || !el.isJsonObject()) {
                    continue;
                }
                try {
                    Ap3Node node = readNode(el.getAsJsonObject());
                    if (node != null) {
                        chain.nodes().add(node);
                    }
                } catch (Exception e) {
                    // skip just this node; the rest of the chain still loads
                }
            }
        }
        return chain;
    }

    private static Ap3Node readNode(JsonObject o) {
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
        // Re-snapped on load: a hand-edited "x": 100.37 still lands on the half-block grid (killer560's snapping rule
        // applies to EVERY node, however it got there).
        Ap3Node n = new Ap3Node(type, Ap3Node.snapXZ(x), Ap3Node.snapY(y), Ap3Node.snapXZ(z),
                ConfigJson.getFloat(o, "yaw", 0f),
                Math.max(-90f, Math.min(90f, ConfigJson.getFloat(o, "pitch", 0f))));
        n.setLength(ConfigJson.getDouble(o, "length", Ap3Node.DEFAULT_LENGTH));
        n.setWidth(ConfigJson.getDouble(o, "width", Ap3Node.DEFAULT_WIDTH));
        n.setWaitMs(ConfigJson.getInt(o, "waitMs", 1000));
        n.leapMode = ConfigJson.getEnum(o, "leapMode", Ap3Node.LeapMode.class, Ap3Node.LeapMode.DEFAULT);
        n.leapClass = DungeonClass.byName(ConfigJson.getString(o, "leapClass", null));
        n.leapIgn = cleanString(ConfigJson.getString(o, "leapIgn", null), MAX_IGN);
        n.setLeapCount(ConfigJson.getInt(o, "leapCount", 1));
        n.wallAxis = ConfigJson.getEnum(o, "wallAxis", Ap3Node.WallAxis.class, Ap3Node.WallAxis.NONE);
        double wall = ConfigJson.getDouble(o, "wallDistance", 0.0);
        n.wallDistance = Double.isFinite(wall) ? Math.max(0.0, Math.min(16.0, wall)) : 0.0;
        String colour = ConfigJson.getString(o, "colour", null);
        if (colour != null) {
            n.colour = parseColour(colour);
        }
        JsonArray blocks = ConfigJson.getArray(o, "blocks");
        if (blocks != null) {
            for (JsonElement b : blocks) {
                if (n.breakerBlocks.size() >= MAX_BREAKER_BLOCKS) {
                    break;
                }
                BlockPos pos = parseBlock(b);
                if (pos != null && !n.breakerBlocks.contains(pos)) {
                    n.breakerBlocks.add(pos);
                }
            }
        }
        return n;
    }

    private static JsonObject writeChain(Ap3Chain chain) {
        JsonObject obj = new JsonObject();
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
        o.addProperty("x", round(n.x, 3));
        o.addProperty("y", round(n.y, 3));
        o.addProperty("z", round(n.z, 3));
        o.addProperty("yaw", round(n.yaw, 1));
        o.addProperty("pitch", round(n.pitch, 1));
        if (n.type.isCorridor() || n.type.isMover()) {
            o.addProperty("length", round(n.length, 2));
        }
        if (n.type.isCorridor()) {
            o.addProperty("width", round(n.width, 2));
        }
        switch (n.type) {
            case AXIS_LINE -> {
                o.addProperty("wallAxis", n.wallAxis.name());
                o.addProperty("wallDistance", round(n.wallDistance, 3));
            }
            case WAIT -> o.addProperty("waitMs", n.waitMs);
            case LEAP -> {
                o.addProperty("leapMode", n.leapMode.name());
                o.addProperty("leapClass", n.leapClass == null ? "" : n.leapClass.name());
                o.addProperty("leapIgn", n.leapIgn == null ? "" : n.leapIgn);
            }
            case LEAP_DETECTOR -> o.addProperty("leapCount", n.leapCount);
            case BREAKER -> {
                JsonArray blocks = new JsonArray();
                for (BlockPos b : n.breakerBlocks) {
                    blocks.add(b.getX() + " " + b.getY() + " " + b.getZ());
                }
                o.add("blocks", blocks);
            }
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

    /** {@code "x y z"} (this file's own form) or a {@code [x, y, z]} array (hand-written), ints only. */
    private static BlockPos parseBlock(JsonElement el) {
        try {
            int[] v = new int[3];
            if (el.isJsonArray()) {
                JsonArray a = el.getAsJsonArray();
                if (a.size() != 3) {
                    return null;
                }
                for (int i = 0; i < 3; i++) {
                    v[i] = a.get(i).getAsInt();
                }
            } else {
                String[] parts = el.getAsString().trim().split("[\\s,]+");
                if (parts.length != 3) {
                    return null;
                }
                for (int i = 0; i < 3; i++) {
                    v[i] = Integer.parseInt(parts[i]);
                }
            }
            for (int c : v) {
                if (Math.abs(c) > MAX_ABS_COORD) {
                    return null;
                }
            }
            return new BlockPos(v[0], v[1], v[2]);
        } catch (Exception e) {
            return null;
        }
    }
}
