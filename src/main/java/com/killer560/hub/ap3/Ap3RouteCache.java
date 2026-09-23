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
    /** How many different ways of arriving at one route are remembered. */
    private static final int MAX_PER_ROUTE = 8;

    private Ap3RouteCache() {
    }

    /**
     * Read from the client thread and written from the planning worker, so it cannot be a plain HashMap: a put
     * racing a get can corrupt a bucket, and iterating it to save while another thread puts throws.
     */
    private static final Map<String, List<Entry>> ENTRIES = new java.util.concurrent.ConcurrentHashMap<>();
    private static boolean loaded;

    static final class Entry {
        String signature = "";
        double startX, startY, startZ, startSpeed;
        /** The velocity it was planned from, not just its magnitude - see lookup(). */
        double startVx, startVz;
        boolean onGround, sprinting, crouching;
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

    /** How far an entry is from this start, or NaN when it is not a match at all. */
    private static double distance(Entry e, Ap3RouteMath.RouteState start) {
        double away = Math.sqrt((start.x - e.startX) * (start.x - e.startX)
                + (start.y - e.startY) * (start.y - e.startY)
                + (start.z - e.startZ) * (start.z - e.startZ));
        if (away > START_TOLERANCE) {
            return Double.NaN;
        }
        // The DIRECTION he is moving matters as much as the speed: a schedule built for someone arriving from the
        // north is wrong from its first tick for someone arriving from the south at the same pace. Comparing only
        // the magnitude handed those plans straight back.
        if (Math.hypot(start.vx - e.startVx, start.vz - e.startVz) > SPEED_TOLERANCE) {
            return Double.NaN;
        }
        if (start.onGround != e.onGround || start.sprinting != e.sprinting) {
            return Double.NaN;
        }
        return away;
    }

    /**
     * The saved plan for this route if one was planned from near enough to here.
     * <p>
     * A route keeps SEVERAL plans, one per way of arriving at it. A plan is a fixed schedule of keys from a
     * particular state, so it can only be handed back to someone starting from close to that state - and with a
     * single saved plan per route that meant stepping onto the node a metre off, or at a different speed, threw the
     * saved work away and searched again. killer560 (2026-09-22): "it doesnt quite feel like it is saving the
     * movement." It does now: the first few runs fill in the approaches he actually uses, and after that the same
     * approach gets the same path every time.
     */
    static Ap3RoutePlanner.Plan lookup(String signature, Ap3RouteMath.RouteState start) {
        load();
        List<Entry> list = ENTRIES.get(signature);
        if (list == null || list.isEmpty()) {
            LOGGER.info("[AP3 route] nothing saved for this route yet - searching");
            return null;
        }
        Entry best = null;
        double bestAway = Double.MAX_VALUE;
        for (Entry e : list) {
            double d = distance(e, start);
            if (!Double.isNaN(d) && d < bestAway) {
                best = e;
                bestAway = d;
            }
        }
        if (best == null) {
            double nearest = Double.MAX_VALUE;
            for (Entry e : list) {
                nearest = Math.min(nearest, Math.hypot(start.x - e.startX, start.z - e.startZ));
            }
            LOGGER.info("[AP3 route] {} saved plan(s) for this route but none from here (nearest start {} blocks"
                    + " away, v {}) - searching", list.size(),
                    String.format(Locale.US, "%.2f", nearest),
                    String.format(Locale.US, "%.3f", start.speed()));
            return null;
        }
        LOGGER.info("[AP3 route] using a saved {}-tick plan ({} blocks from where it was planned, {} kept for this"
                + " route)", best.ticks, String.format(Locale.US, "%.2f", bestAway), list.size());
        Ap3RoutePlanner.Plan p = new Ap3RoutePlanner.Plan();
        p.steps = best.steps.toArray(new Ap3RoutePlanner.Step[0]);
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
        List<Entry> list = ENTRIES.computeIfAbsent(signature, k -> new java.util.concurrent.CopyOnWriteArrayList<>());
        // Replace the entry for THIS approach if this plan beats it; otherwise add a new approach alongside the
        // ones already known, so a route builds up an answer for each way he arrives at it.
        Entry old = null;
        double bestAway = Double.MAX_VALUE;
        for (Entry candidate : list) {
            double d = distance(candidate, start);
            if (!Double.isNaN(d) && d < bestAway) {
                old = candidate;
                bestAway = d;
            }
        }
        if (old != null && old.ticks <= plan.ticks) {
            return; // what is saved for this approach is at least as quick
        }
        Entry e = new Entry();
        e.signature = signature;
        e.startX = start.x;
        e.startY = start.y;
        e.startZ = start.z;
        e.startSpeed = start.speed();
        e.startVx = start.vx;
        e.startVz = start.vz;
        e.onGround = start.onGround;
        e.sprinting = start.sprinting;
        e.crouching = start.crouching;
        e.ticks = plan.ticks;
        e.steps = new ArrayList<>(List.of(plan.steps));
        if (old != null) {
            list.remove(old);
        }
        list.add(e);
        while (list.size() > MAX_PER_ROUTE) {
            list.remove(0);
        }
        LOGGER.info("[AP3 route] saved a {}-tick plan for this route{} - {} approach(es) now known", plan.ticks,
                old == null ? "" : " (was " + old.ticks + ")", list.size());
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
        int n = 0;
        for (List<Entry> l : ENTRIES.values()) {
            n += l.size();
        }
        return n;
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
                    e.startVx = o.has("vx") ? o.get("vx").getAsDouble() : 0;
                    e.startVz = o.has("vz") ? o.get("vz").getAsDouble() : 0;
                    e.onGround = !o.has("g") || o.get("g").getAsBoolean();
                    e.sprinting = o.has("sp") && o.get("sp").getAsBoolean();
                    e.crouching = o.has("cr") && o.get("cr").getAsBoolean();
                    JsonArray steps = o.getAsJsonArray("steps");
                    for (int k = 0; k < steps.size(); k++) {
                        JsonObject st = steps.get(k).getAsJsonObject();
                        e.steps.add(new Ap3RoutePlanner.Step(
                                new Ap3DiscretePlanner.Action(st.get("f").getAsInt(), st.get("s").getAsInt(),
                                        st.get("c").getAsBoolean()),
                                st.get("yaw").getAsFloat(), st.get("j").getAsBoolean()));
                    }
                    e.ticks = e.steps.size();
                    ENTRIES.computeIfAbsent(e.signature,
                            k -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(e);
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
            List<Entry> flat = new ArrayList<>();
            for (List<Entry> l : ENTRIES.values()) {
                flat.addAll(l);
            }
            for (Entry e : flat) {
                JsonObject o = new JsonObject();
                o.addProperty("sig", e.signature);
                o.addProperty("x", e.startX);
                o.addProperty("y", e.startY);
                o.addProperty("z", e.startZ);
                o.addProperty("v", e.startSpeed);
                o.addProperty("vx", e.startVx);
                o.addProperty("vz", e.startVz);
                o.addProperty("g", e.onGround);
                o.addProperty("sp", e.sprinting);
                o.addProperty("cr", e.crouching);
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
