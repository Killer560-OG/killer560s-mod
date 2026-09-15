package com.killer560.hub.routes;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * Clipboard import/export. Formats confirmed from the real SkyHanni 7.22.0 and Skyblocker 6.4.1 jars (javap):
 * <ul>
 * <li><b>ColeWeight / Soopy ordered</b> (SkyHanni {@code ColeweightWaypointFormat}, Skyblocker
 * {@code fromColeweightJson}): {@code [{"x":int,"y":int,"z":int,"r":0-1,"g":0-1,"b":0-1,"options":{"name":"1"}}]}.
 * SkyHanni orders by {@code Integer.parseInt(options.name)}, so export always writes the 1-based index there.</li>
 * <li><b>Skytils</b> ({@code SkytilsWaypointFormat}): {@code <Skytils-Waypoint-Data>(V1):} + base64(gzip(json)),
 * json being {@code {"categories":[{"name","island","waypoints":[{"name","x","y","z","color"}]}]}} or a bare array.</li>
 * </ul>
 * Skyblocker's own {@code [Skyblocker-Waypoint-Data-V1]} codec format is not supported.
 */
public final class RouteCodec {

    private static final String SKYTILS_PREFIX = "<Skytils-Waypoint-Data>(V";
    private static final String SKYBLOCKER_PREFIX = "[Skyblocker";
    private static final int MAX_INFLATED_BYTES = 8 * 1024 * 1024;

    private RouteCodec() {
    }

    public record Imported(String name, Integer color, List<Route.Point> points) {
    }

    public record Result(List<Imported> routes, String error) {
        static Result fail(String error) {
            return new Result(List.of(), error);
        }
    }

    public static Result parse(String clipboard) {
        if (clipboard == null || clipboard.isBlank()) {
            return Result.fail("Clipboard is empty.");
        }
        String text = clipboard.trim();
        if (text.startsWith(SKYBLOCKER_PREFIX)) {
            return Result.fail("Skyblocker's share format isn't supported - export as Coleweight or Skytils.");
        }
        try {
            if (text.startsWith(SKYTILS_PREFIX)) {
                int split = text.indexOf("):");
                if (split < 0) {
                    return Result.fail("Malformed Skytils waypoint data.");
                }
                text = gunzipBase64(text.substring(split + 2).trim());
            } else if (!text.startsWith("[") && !text.startsWith("{")) {
                text = gunzipBase64(text);
            }
            JsonElement root = JsonParser.parseString(text);
            List<Imported> routes = new ArrayList<>();
            if (root.isJsonArray()) {
                Imported single = fromArray("Imported Route", root.getAsJsonArray());
                if (!single.points().isEmpty()) {
                    routes.add(single);
                }
            } else if (root.isJsonObject()) {
                JsonObject obj = root.getAsJsonObject();
                if (obj.has("categories")) {
                    for (JsonElement cat : obj.getAsJsonArray("categories")) {
                        JsonObject c = cat.getAsJsonObject();
                        if (!c.has("waypoints")) {
                            continue;
                        }
                        Imported imported = fromArray(string(c, "name", "Imported Route"), c.getAsJsonArray("waypoints"));
                        if (!imported.points().isEmpty()) {
                            routes.add(imported);
                        }
                    }
                } else if (obj.has("waypoints")) {
                    Imported imported = fromArray(string(obj, "name", "Imported Route"), obj.getAsJsonArray("waypoints"));
                    if (!imported.points().isEmpty()) {
                        routes.add(imported);
                    }
                }
            }
            return routes.isEmpty() ? Result.fail("No waypoints found in clipboard.") : new Result(routes, null);
        } catch (Exception e) {
            return Result.fail("Clipboard isn't a recognised waypoint list.");
        }
    }

    private static Imported fromArray(String name, JsonArray array) {
        record Entry(Route.Point point, Integer order) {
        }
        List<Entry> entries = new ArrayList<>();
        Integer color = null;
        boolean allOrdered = true;
        for (JsonElement el : array) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject o = el.getAsJsonObject();
            if (!o.has("x") || !o.has("y") || !o.has("z")) {
                continue;
            }
            String label = "";
            if (o.has("options") && o.get("options").isJsonObject()) {
                label = string(o.getAsJsonObject("options"), "name", "");
            } else if (o.has("name")) {
                label = string(o, "name", "");
            }
            Integer order = null;
            try {
                order = Integer.parseInt(label.trim());
                label = "";
            } catch (NumberFormatException ignored) {
                allOrdered = false;
            }
            if (color == null) {
                color = colorOf(o);
            }
            entries.add(new Entry(new Route.Point((int) Math.floor(o.get("x").getAsDouble()),
                    (int) Math.floor(o.get("y").getAsDouble()), (int) Math.floor(o.get("z").getAsDouble()), label), order));
        }
        if (allOrdered && !entries.isEmpty()) {
            entries.sort((a, b) -> Integer.compare(a.order(), b.order())); // List.sort is stable
        }
        List<Route.Point> points = new ArrayList<>();
        for (Entry e : entries) {
            points.add(e.point());
        }
        return new Imported(name, color, points);
    }

    private static Integer colorOf(JsonObject o) {
        if (o.has("r") && o.has("g") && o.has("b")) {
            double r = o.get("r").getAsDouble();
            double g = o.get("g").getAsDouble();
            double b = o.get("b").getAsDouble();
            boolean unit = r <= 1.0 && g <= 1.0 && b <= 1.0; // Coleweight uses 0-1; tolerate 0-255
            int ri = channel(unit ? r * 255 : r);
            int gi = channel(unit ? g * 255 : g);
            int bi = channel(unit ? b * 255 : b);
            return 0xFF000000 | (ri << 16) | (gi << 8) | bi;
        }
        if (o.has("color") && o.get("color").isJsonPrimitive()) {
            // Review fix (2026-09-15): a non-numeric color (e.g. "#ff0000") used to throw and fail the whole import.
            try {
                return 0xFF000000 | (o.get("color").getAsInt() & 0xFFFFFF);
            } catch (RuntimeException ignored) {
                return null;
            }
        }
        return null;
    }

    private static int channel(double v) {
        return (int) Math.max(0, Math.min(255, Math.round(v)));
    }

    /** ColeWeight / Soopy ordered-waypoint JSON - readable by ColeWeight, SkyHanni /sho and Skyblocker. */
    public static String toColeweight(Route route) {
        double r = Math.round(((route.color >> 16) & 0xFF) / 255.0 * 1000) / 1000.0;
        double g = Math.round(((route.color >> 8) & 0xFF) / 255.0 * 1000) / 1000.0;
        double b = Math.round((route.color & 0xFF) / 255.0 * 1000) / 1000.0;
        JsonArray arr = new JsonArray();
        for (int i = 0; i < route.points.size(); i++) {
            Route.Point p = route.points.get(i);
            JsonObject o = new JsonObject();
            o.addProperty("x", p.x());
            o.addProperty("y", p.y());
            o.addProperty("z", p.z());
            o.addProperty("r", r);
            o.addProperty("g", g);
            o.addProperty("b", b);
            JsonObject options = new JsonObject();
            options.addProperty("name", String.valueOf(i + 1));
            o.add("options", options);
            arr.add(o);
        }
        return new Gson().toJson(arr);
    }

    private static String gunzipBase64(String data) throws Exception {
        byte[] bytes = Base64.getDecoder().decode(data.replaceAll("\\s", ""));
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
            // Review fix (2026-09-15): cap the inflated size so a hostile/garbage clipboard gzip can't OOM the
            // client on the render thread (the import button runs there).
            byte[] out = in.readNBytes(MAX_INFLATED_BYTES + 1);
            if (out.length > MAX_INFLATED_BYTES) {
                throw new IllegalArgumentException("Waypoint data too large");
            }
            return new String(out, StandardCharsets.UTF_8);
        }
    }

    private static String string(JsonObject o, String key, String def) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : def;
    }
}
