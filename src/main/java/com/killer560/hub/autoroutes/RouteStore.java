package com.killer560.hub.autoroutes;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
 * All Auto Routes, in ONE shareable file: {@code config/killer560smod-autoroutes.json} - killer560: "I am fine
 * with it being a json file as long as it is easy to edit in notepad and share really easily. I should only have to
 * share one file." Settings live in their own file ({@link AutoRoutesConfig}), so this one is nothing but routes.
 * <p>
 * Layout, chosen so a person can find a room, nudge a node and {@code /ar reload}:
 * <pre>
 * { "version": 1,
 *   "routes": {
 *     "Room Name": {
 *       "nodes": [ { "type": "ETHERWARP", "x": 12.5, "y": 69.0, "z": 4.5, "yaw": 90.0, "pitch": 45.0, "at": 37, ... } ],
 *       "pathNote": "recorded movement - edit the nodes above, not this",
 *       "path": "x y z yaw pitch keys ground;..."   (one line, see RoutePath.encode)
 *     } } }
 * </pre>
 * Loading is defensive because this file is meant to be handed around: node/sample counts and string lengths are
 * capped, NaN/infinite/absurd coordinates are dropped, a malformed node is skipped (the rest of the room loads), and
 * a file whose parse fails is copied aside as {@code killer560smod-autoroutes.broken.json (plus a timestamped sibling for any later, different corruption)} and never saved
 * over - the same rule {@code routes/RouteStore} and {@code posmsg/PosmsgConfig} follow.
 */
public final class RouteStore {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-autoroutes");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "killer560smod-autoroutes.json";
    private static final int FORMAT_VERSION = 1;

    // Proportionate caps for a friend's file, not a network input.
    public static final int MAX_ROUTES = 500;
    public static final int MAX_NODES = 200;
    /** 30 minutes at 20 ticks/s - far beyond any real room route. */
    public static final int MAX_SAMPLES = 36_000;
    public static final int MAX_BREAKER_BLOCKS = 20;
    public static final int MAX_ROOM_NAME = 100;
    public static final int MAX_ITEM_ID = 100;
    public static final int MAX_COMMAND = 256;
    /** Room-relative coordinates live within one dungeon room; anything past this is garbage. */
    public static final double MAX_ABS_COORD = 512.0;
    public static final String PATH_NOTE = "recorded movement - edit the nodes above, not this";

    private static RouteStore instance;

    private final Map<String, Route> routes = new LinkedHashMap<>();
    /** Set when the last load could not parse the file: {@link #save()} refuses to overwrite it until a load succeeds
     *  or the user explicitly makes a change (which starts from the recovered/empty state they can see). */
    private boolean parseFailed;

    private RouteStore() {
    }

    public static RouteStore getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    /** Folder containing the routes file (for the tab's "Open Routes Folder" button); created if missing. */
    public static Path routesDirectory() {
        Path dir = FabricLoader.getInstance().getConfigDir();
        try {
            Files.createDirectories(dir);
        } catch (Exception ignored) {
        }
        return dir;
    }

    public static Path routesFile() {
        return routesDirectory().resolve(FILE_NAME);
    }

    /** {@code /ar reload}: re-read the file, replacing what is in memory. Stops a running route first rather than
     *  swapping the node list out from under the executor. Safe from {@code ProfileManager.reloadAllConfigs}. */
    public static void reload() {
        if (RouteExecutor.isRunning()) {
            RouteExecutor.stop("routes reloaded");
        }
        if (RouteRecorder.isRecording()) {
            RouteRecorder.discard();
        }
        load();
        AutoRoutesFeature.onRoutesReloaded();
    }

    public static void load() {
        RouteStore store = new RouteStore();
        Path file = routesFile();
        if (Files.exists(file)) {
            try {
                JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
                JsonObject routesObj = ConfigJson.getObject(root, "routes");
                if (routesObj != null) {
                    for (String roomName : routesObj.keySet()) {
                        if (store.routes.size() >= MAX_ROUTES) {
                            LOGGER.warn("[AutoRoutes] More than {} routes in {} - the rest were ignored", MAX_ROUTES, FILE_NAME);
                            break;
                        }
                        String name = cleanString(roomName, MAX_ROOM_NAME);
                        JsonObject routeObj = ConfigJson.getObject(routesObj, roomName);
                        if (name == null || routeObj == null) {
                            continue;
                        }
                        try {
                            Route route = readRoute(name, routeObj);
                            if (!route.isEmpty()) {
                                store.routes.put(name, route);
                            }
                        } catch (Exception e) {
                            LOGGER.warn("[AutoRoutes] Skipping unreadable route for \"{}\": {}", name, e.toString());
                        }
                    }
                }
                LOGGER.info("[AutoRoutes] Loaded {} route(s) from {}", store.routes.size(), FILE_NAME);
            } catch (Exception e) {
                store.parseFailed = true;
                store.routes.clear();
                LOGGER.warn("[AutoRoutes] Failed to parse {} - backing it up, nothing will be saved over it", FILE_NAME, e);
                try {
                    // One backup per broken file, not one per reload: /ar reload and every profile switch
                    // come back through here, and the original is already safe after the first copy
                    // (2026-09-16 review).
                    Path backup = file.resolveSibling("killer560smod-autoroutes.broken.json");
                    if (!Files.exists(backup)) {
                        Files.copy(file, backup);
                    } else if (Files.mismatch(file, backup) != -1L) {
                        // A DIFFERENT corruption than the one already kept. Skipping it meant the next
                        // /ar add cleared parseFailed and saved straight over it, losing the file with no
                        // backup at all (2026-09-16 review).
                        Files.copy(file, file.resolveSibling(
                                "killer560smod-autoroutes.broken-" + System.currentTimeMillis() + ".json"));
                    }
                } catch (Exception backupError) {
                    LOGGER.warn("[AutoRoutes] Could not back up the unreadable routes file", backupError);
                }
            }
        }
        instance = store;
    }

    /** Writes every route. Refuses while the file on disk failed to parse and nothing has been changed since, so a
     *  half-downloaded file from a friend can't be replaced by an empty one behind the user's back. */
    public void save() {
        if (parseFailed) {
            LOGGER.warn("[AutoRoutes] Not saving {}: the file on disk could not be parsed (see the .broken backup)", FILE_NAME);
            return;
        }
        try {
            Path file = routesFile();
            Files.createDirectories(file.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("version", FORMAT_VERSION);
            root.addProperty("note", "Auto Routes - one route per dungeon room, room-relative coordinates. Edit the nodes, then /ar reload.");
            JsonObject routesObj = new JsonObject();
            for (Route route : routes.values()) {
                routesObj.add(route.roomName(), writeRoute(route));
            }
            root.add("routes", routesObj);
            Files.writeString(file, GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.warn("[AutoRoutes] Failed to save {}", FILE_NAME, e);
        }
    }

    // ------------------------------------------------------------------------------------------- access

    /** Routes by room name (unmodifiable view). Mutate through {@link #put}/{@link #remove}/{@link #forRoom}. */
    public Map<String, Route> routes() {
        return Collections.unmodifiableMap(routes);
    }

    public List<Route> all() {
        return new ArrayList<>(routes.values());
    }

    public List<String> roomNames() {
        return new ArrayList<>(routes.keySet());
    }

    /** @return the route for this room, or null when none is saved. */
    public Route forRoom(String roomName) {
        return roomName == null ? null : routes.get(roomName);
    }

    /** The route for this room, created empty if missing (not saved until {@link #save()}). */
    public Route forRoomOrCreate(String roomName) {
        Route route = routes.get(roomName);
        if (route == null) {
            route = new Route(roomName);
            routes.put(roomName, route);
            parseFailed = false; // an explicit new route is the user's own decision to start over
        }
        return route;
    }

    public void put(Route route) {
        if (route != null) {
            routes.put(route.roomName(), route);
            parseFailed = false;
        }
    }

    public boolean remove(String roomName) {
        boolean removed = roomName != null && routes.remove(roomName) != null;
        if (removed) {
            parseFailed = false;
        }
        return removed;
    }

    public boolean lastLoadFailed() {
        return parseFailed;
    }

    // ------------------------------------------------------------------------------------------- codec

    private static Route readRoute(String name, JsonObject obj) {
        Route route = new Route(name);
        JsonArray nodes = ConfigJson.getArray(obj, "nodes");
        if (nodes != null) {
            for (JsonElement el : nodes) {
                if (route.nodes().size() >= MAX_NODES) {
                    LOGGER.warn("[AutoRoutes] Route \"{}\" has more than {} nodes - the rest were ignored", name, MAX_NODES);
                    break;
                }
                if (el == null || !el.isJsonObject()) {
                    continue;
                }
                try {
                    RouteNode node = readNode(el.getAsJsonObject());
                    if (node != null) {
                        route.nodes().add(node);
                    }
                } catch (Exception e) {
                    // skip just this node; the rest of the room still loads
                }
            }
        }
        route.setPath(RoutePath.decode(ConfigJson.getString(obj, "path", ""), MAX_SAMPLES, MAX_ABS_COORD));
        route.clampNodeAnchors();
        return route;
    }

    private static RouteNode readNode(JsonObject o) {
        RouteNode.Type type = RouteNode.Type.parse(ConfigJson.getString(o, "type", null));
        if (type == null) {
            return null;
        }
        double x = ConfigJson.getDouble(o, "x", Double.NaN);
        double y = ConfigJson.getDouble(o, "y", Double.NaN);
        double z = ConfigJson.getDouble(o, "z", Double.NaN);
        if (!finiteCoord(x) || !finiteCoord(y) || !finiteCoord(z)) {
            return null;
        }
        RouteNode n = new RouteNode(type, x, y, z,
                ConfigJson.getFloat(o, "yaw", 0f),
                Math.max(-90f, Math.min(90f, ConfigJson.getFloat(o, "pitch", 0f))),
                Math.max(0, ConfigJson.getInt(o, "at", 0)));
        n.radius = Math.max(0.2, Math.min(8.0, ConfigJson.getDouble(o, "radius", RouteNode.DEFAULT_RADIUS)));
        String colour = ConfigJson.getString(o, "colour", null);
        if (colour != null) {
            n.colour = parseColour(colour);
        }
        n.item = cleanString(ConfigJson.getString(o, "item", null), MAX_ITEM_ID);
        n.command = cleanString(ConfigJson.getString(o, "command", null), MAX_COMMAND);
        n.awaitCondition = ConfigJson.getEnum(o, "await", RouteNode.AwaitCondition.class, RouteNode.AwaitCondition.SECRET);
        n.awaitAmount = Math.max(0, Math.min(600_000, ConfigJson.getInt(o, "amount", 1)));
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
        String landing = ConfigJson.getString(o, "landing", null);
        if (landing != null) {
            double[] v = parseTriple(landing);
            if (v != null) {
                n.landingX = v[0];
                n.landingY = v[1];
                n.landingZ = v[2];
                n.hasLanding = true;
            }
        }
        return n;
    }

    private static JsonObject writeRoute(Route route) {
        JsonObject obj = new JsonObject();
        JsonArray nodes = new JsonArray();
        for (RouteNode n : route.nodes()) {
            nodes.add(writeNode(n));
        }
        obj.add("nodes", nodes);
        obj.addProperty("pathNote", PATH_NOTE);
        obj.addProperty("path", route.path().encode());
        return obj;
    }

    private static JsonObject writeNode(RouteNode n) {
        JsonObject o = new JsonObject();
        o.addProperty("type", n.type.name());
        o.addProperty("x", round(n.x, 3));
        o.addProperty("y", round(n.y, 3));
        o.addProperty("z", round(n.z, 3));
        o.addProperty("yaw", round(n.yaw, 1));
        o.addProperty("pitch", round(n.pitch, 1));
        o.addProperty("at", n.pathIndex);
        if (n.radius != RouteNode.DEFAULT_RADIUS) {
            o.addProperty("radius", round(n.radius, 2));
        }
        if (n.colour != null) {
            o.addProperty("colour", String.format(Locale.ROOT, "#%08X", n.colour));
        }
        switch (n.type) {
            case USE_ITEM -> o.addProperty("item", n.item == null ? "" : n.item);
            case DUNGEON_BREAKER -> {
                JsonArray blocks = new JsonArray();
                for (BlockPos b : n.breakerBlocks) {
                    blocks.add(b.getX() + " " + b.getY() + " " + b.getZ());
                }
                o.add("blocks", blocks);
            }
            case AWAIT -> {
                o.addProperty("await", n.awaitCondition.name());
                o.addProperty("amount", n.awaitAmount);
            }
            case COMMAND -> o.addProperty("command", n.command == null ? "" : n.command);
            default -> {
            }
        }
        if (n.hasLanding) {
            o.addProperty("landing", String.format(Locale.US, "%.3f %.3f %.3f", n.landingX, n.landingY, n.landingZ));
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

    private static double[] parseTriple(String s) {
        try {
            String[] parts = s.trim().split("[\\s,]+");
            if (parts.length != 3) {
                return null;
            }
            double[] v = new double[3];
            for (int i = 0; i < 3; i++) {
                v[i] = Double.parseDouble(parts[i]);
                if (!finiteCoord(v[i])) {
                    return null;
                }
            }
            return v;
        } catch (Exception e) {
            return null;
        }
    }
}
