package com.killer560.hub.autoclear;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ModLog;
import org.slf4j.Logger;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Bundled clear cost of each mob room (autopilot-instaclear-roomcost-1126, killer560 2026-11-26: "don't just go based off
 * of room size ... some 1x2s will take far longer than a 2x2 that only has one boss mob in it ... Slime is awful ...
 * Cathedral is very simple"): seconds per room from where its mobs really spawn (the Blood Rush pack boxes: how many, the
 * walk through them, their height spread, a miniboss), fitted to his own learned clear times, made by
 * {@code tools/autopilot/build-room-clear-cost.py} into {@code autopilot/room-clear-cost.json} (under 2 kB). Read once on
 * first use, never at boot. {@link #scaled} keeps the caller's own learned scale and takes only the table's shape.
 */
public final class RoomClearTable {

    private static final Logger LOGGER = ModLog.get("killer560smod-autoclear");
    private static final String FILE = "/assets/killer560smod/autopilot/room-clear-cost.json";
    private static volatile Map<String, Double> table;
    private static double mean = 1;

    private RoomClearTable() {
    }

    /** The table's seconds for {@code room}, or NaN when it has none (not a mob room it knows). */
    public static double seconds(String room) {
        Double s = room == null ? null : load().get(room);
        return s == null ? Double.NaN : s;
    }

    /** {@code guess} (one figure for every room) shaped by the table: a room the table rates at twice the average costs
     *  twice the guess; a room it does not know keeps the guess. */
    public static double scaled(String room, double guess) {
        double s = seconds(room);
        return Double.isNaN(s) ? guess : guess * s / mean;
    }

    private static Map<String, Double> load() {
        Map<String, Double> t = table;
        if (t != null) {
            return t;
        }
        Map<String, Double> out = new HashMap<>();
        try (InputStream in = RoomClearTable.class.getResourceAsStream(FILE)) {
            if (in != null) {
                JsonObject s = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject()
                        .getAsJsonObject("s");
                for (Map.Entry<String, JsonElement> e : s.entrySet()) {
                    out.put(e.getKey(), e.getValue().getAsDouble());
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Room clear table could not be read: {}", e.toString());
        }
        double sum = 0;
        for (double v : out.values()) {
            sum += v;
        }
        mean = out.isEmpty() ? 1 : sum / out.size();
        table = Map.copyOf(out);
        return table;
    }
}
