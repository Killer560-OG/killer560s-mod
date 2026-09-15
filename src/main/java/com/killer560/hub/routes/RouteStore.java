package com.killer560.hub.routes;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Saved routes, in their own file ({@code killer560smod-waypointroutes-routes.json}) separate from the
 *  feature's settings. Every mutation is followed by {@link #save()} from the caller. */
public final class RouteStore {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-routes");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-waypointroutes-routes.json");

    private static List<Route> routes;

    private RouteStore() {
    }

    public static List<Route> all() {
        if (routes == null) {
            load();
        }
        return routes;
    }

    public static Route byId(String id) {
        if (id == null) {
            return null;
        }
        for (Route route : all()) {
            if (route.id.equals(id)) {
                return route;
            }
        }
        return null;
    }

    public static Route create(String name) {
        Route route = new Route(null, uniqueName(name));
        all().add(route);
        return route;
    }

    public static void delete(String id) {
        all().removeIf(route -> route.id.equals(id));
    }

    public static String uniqueName(String base) {
        String clean = base == null || base.isBlank() ? "Route" : base.trim();
        if (!nameTaken(clean)) {
            return clean;
        }
        for (int i = 2; ; i++) {
            String candidate = clean + " " + i;
            if (!nameTaken(candidate)) {
                return candidate;
            }
        }
    }

    private static boolean nameTaken(String name) {
        for (Route route : all()) {
            if (route.name.toLowerCase(Locale.ROOT).equals(name.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    public static void load() {
        routes = new ArrayList<>();
        if (!Files.exists(PATH)) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(PATH, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!root.has("routes")) {
                return;
            }
            for (JsonElement el : root.getAsJsonArray("routes")) {
                JsonObject obj = el.getAsJsonObject();
                Route route = new Route(str(obj, "id", null), str(obj, "name", "Route"));
                route.color = obj.has("color") ? obj.get("color").getAsInt() : Route.DEFAULT_COLOR;
                route.setRadius(obj.has("radius") ? obj.get("radius").getAsDouble() : Route.DEFAULT_RADIUS);
                route.loop = !obj.has("loop") || obj.get("loop").getAsBoolean();
                if (obj.has("points")) {
                    for (JsonElement pe : obj.getAsJsonArray("points")) {
                        JsonObject p = pe.getAsJsonObject();
                        route.points.add(new Route.Point(p.get("x").getAsInt(), p.get("y").getAsInt(),
                                p.get("z").getAsInt(), str(p, "label", "")));
                    }
                }
                routes.add(route);
            }
        } catch (Exception e) {
            LOGGER.warn("[WaypointRoutes] Failed to load routes file, starting empty", e);
        }
    }

    public static void save() {
        try {
            Files.createDirectories(PATH.getParent());
            JsonArray arr = new JsonArray();
            for (Route route : all()) {
                JsonObject obj = new JsonObject();
                obj.addProperty("id", route.id);
                obj.addProperty("name", route.name);
                obj.addProperty("color", route.color);
                obj.addProperty("radius", route.getRadius());
                obj.addProperty("loop", route.loop);
                JsonArray points = new JsonArray();
                for (Route.Point p : route.points) {
                    JsonObject po = new JsonObject();
                    po.addProperty("x", p.x());
                    po.addProperty("y", p.y());
                    po.addProperty("z", p.z());
                    if (!p.label().isEmpty()) {
                        po.addProperty("label", p.label());
                    }
                    points.add(po);
                }
                obj.add("points", points);
                arr.add(obj);
            }
            JsonObject root = new JsonObject();
            root.add("routes", arr);
            Files.writeString(PATH, GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.warn("[WaypointRoutes] Failed to save routes file", e);
        }
    }

    private static String str(JsonObject obj, String key, String def) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : def;
    }
}
