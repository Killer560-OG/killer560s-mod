package com.killer560.hub.ap3;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The best plan a route has ever produced, kept so it does not have to be found again.
 * <p>
 * killer560 (2026-09-22): "it was a different path every time i stepped on it when it should scan and just save
 * one... have it save the most optimal path after you go to run it the first time, that way it will run the exact
 * same every time once it gets the most optimal way."
 * <p>
 * A route is identified by its nodes - where they are, how big they are and what they ask for - so moving or
 * editing any of them gives a different route and a fresh search, while leaving them alone gets the same answer
 * every time. A plan is a schedule of keys from a PARTICULAR starting state, so it is only handed back when he
 * starts from close enough to where it was planned from; step on the node from somewhere quite different and it is
 * planned again, and kept if it turns out shorter. Entries live in the config folder, so the work survives a
 * restart.
 */
final class Ap3RouteCache {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-ap3-route");

    /** How far from where a cached plan was planned he may start and still be given it. */
    private static final double START_TOLERANCE = 0.75;
    /** ...and how differently he may be moving. A schedule assumes the speed it was built for. */
    private static final double SPEED_TOLERANCE = 0.12;
    /** Enough entries for a dungeon's worth of routes without the file growing without end. */
    private static final int MAX_ENTRIES = 64;

    private Ap3RouteCache() {
    }

    private static final Map<String, Entry> ENTRIES = new HashMap<>();
    private static boolean loaded;

    static final class Entry {
        String signature = "";
        double startX, startY, startZ, startSpeed;
        int ticks;
        /** Key combination, yaw and jump per tick, exactly as the planner produced them. */
        List<Ap3RoutePlanner.Step> steps = new ArrayList<>();
    }

    /** What makes this route THIS route: its nodes, in order, with everything that changes the answer. */
    static String signature(List<Ap3Node> route) {
        StringBuilder sb = new StringBuilder();
        for (Ap3Node n : route) {
            sb.append(n.pathIndex).append(':')
                    .append(String.format(Locale.US, "%.3f,%.3f,%.3f", n.x, n.y, n.z)).append(':')
                    .append(String.format(Locale.US, "%.2fx%.2f", n.width, n.length)).append(':')
                    .append(n.precise ? 'e' : '-')
                    .append(n.termWait ? 't' : '-')
                    .append(String.format(Locale.US, "%.2f/%.2f", n.minSpeed, n.maxSpeed));
            if (n.hasDir) {
                sb.append(String.format(Locale.US, ":d%.1f/%.1f", n.dirDeg, n.dirTolDeg));
            }
            sb.append(';');
        }
        return sb.toString();
    }

    /** The saved plan for this route if it was planned from near enough to here, else null. */
    static Ap3RoutePlanner.Plan lookup(String signature, Ap3RouteMath.RouteState start) {
        load();
        Entry e = ENTRIES.get(signature);
        if (e == null) {
            return null;
        }
        double away = Math.sqrt((start.x - e.startX) * (start.x - e.startX)
                + (start.y - e.startY) * (start.y - e.startY)
                + (start.z - e.startZ) * (start.z - e.startZ));
        if (away > START_TOLERANCE || Math.abs(start.speed() - e.startSpeed) > SPEED_TOLERANCE) {
            return null;
        }
        Ap3RoutePlanner.Plan p = new Ap3RoutePlanner.Plan();
        p.steps = e.steps.toArray(new Ap3RoutePlanner.Step[0]);
        p.ticks = p.steps.length;
        p.complete = true;
        p.note = "saved";
        p.gateTick = new int[0];
        return p;
    }

    /** Keep this plan if the route has nothing better. Only complete plans are worth remembering. */
    static void offer(String signature, Ap3RouteMath.RouteState start, Ap3RoutePlanner.Plan plan) {
        if (plan == null || !plan.complete || plan.steps.length == 0 || signature.isEmpty()) {
            return;
        }
        load();
        Entry old = ENTRIES.get(signature);
        if (old != null && old.ticks <= plan.ticks) {
            return; // what is saved is at least as quick
        }
        Entry e = new Entry();
        e.signature = signature;
        e.startX = start.x;
        e.startY = start.y;
        e.startZ = start.z;
        e.startSpeed = start.speed();
        e.ticks = plan.ticks;
        e.steps = new ArrayList<>(List.of(plan.steps));
        ENTRIES.put(signature, e);
        LOGGER.info("[AP3 route] saved a {}-tick plan for this route{}", plan.ticks,
                old == null ? "" : " (was " + old.ticks + ")");
        save();
    }

    /** Forget every saved plan - for when the world has changed under them. */
    static int clear() {
        load();
        int n = ENTRIES.size();
        ENTRIES.clear();
        save();
        return n;
    }

    static int size() {
        load();
        return ENTRIES.size();
    }

    // ---- on disk -------------------------------------------------------------------------------------------------

    private static Path file() {
        return Minecraft.getInstance().gameDirectory.toPath()
                .resolve("config").resolve("killer560smod").resolve("ap3-routes.json");
    }

    private static synchronized void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        try {
            Path f = file();
            if (!Files.exists(f)) {
                return;
            }
            JsonObject root = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
            for (JsonArray arr : List.of(root.getAsJsonArray("routes"))) {
                for (int i = 0; i < arr.size(); i++) {
                    JsonObject o = arr.get(i).getAsJsonObject();
                    Entry e = new Entry();
                    e.signature = o.get("sig").getAsString();
                    e.startX = o.get("x").getAsDouble();
                    e.startY = o.get("y").getAsDouble();
                    e.startZ = o.get("z").getAsDouble();
                    e.startSpeed = o.get("v").getAsDouble();
                    JsonArray steps = o.getAsJsonArray("steps");
                    for (int k = 0; k < steps.size(); k++) {
                        JsonObject st = steps.get(k).getAsJsonObject();
                        e.steps.add(new Ap3RoutePlanner.Step(
                                new Ap3DiscretePlanner.Action(st.get("f").getAsInt(), st.get("s").getAsInt(),
                                        st.get("c").getAsBoolean()),
                                st.get("yaw").getAsFloat(), st.get("j").getAsBoolean()));
                    }
                    e.ticks = e.steps.size();
                    ENTRIES.put(e.signature, e);
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("[AP3 route] could not read the saved routes", t);
        }
    }

    private static synchronized void save() {
        try {
            // Oldest out first if it has grown past the limit; a stale plan is only ever a slower first run.
            while (ENTRIES.size() > MAX_ENTRIES) {
                ENTRIES.remove(ENTRIES.keySet().iterator().next());
            }
            JsonObject root = new JsonObject();
            JsonArray arr = new JsonArray();
            for (Entry e : ENTRIES.values()) {
                JsonObject o = new JsonObject();
                o.addProperty("sig", e.signature);
                o.addProperty("x", e.startX);
                o.addProperty("y", e.startY);
                o.addProperty("z", e.startZ);
                o.addProperty("v", e.startSpeed);
                JsonArray steps = new JsonArray();
                for (Ap3RoutePlanner.Step st : e.steps) {
                    JsonObject s = new JsonObject();
                    s.addProperty("f", st.keys().fw());
                    s.addProperty("s", st.keys().st());
                    s.addProperty("c", st.keys().sneak());
                    s.addProperty("yaw", st.yaw());
                    s.addProperty("j", st.jump());
                    steps.add(s);
                }
                o.add("steps", steps);
                arr.add(o);
            }
            root.add("routes", arr);
            Path f = file();
            Files.createDirectories(f.getParent());
            Files.writeString(f, root.toString(), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException t) {
            LOGGER.warn("[AP3 route] could not save the routes", t);
        }
    }
}
