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
    /**
     * Measured against his own misses: the log showed saved plans sitting 1.00, 1.27 and 1.38 blocks from where he
     * stepped on, all refused at 0.75. A schedule from a metre away is not perfect - the drift check will correct
     * it within a few ticks - but it is a great deal better than searching from scratch, and with several
     * approaches kept per route the near ones win anyway.
     */
    private static final double START_TOLERANCE = 1.25;
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
        if (start.onGround != e.onGround) {
            return Double.NaN;
        }
        // Whether the sprint key is down only matters if he is actually moving. Standing on the node it decides
        // nothing - vanilla drops sprint the moment there is no forward impulse - but comparing it anyway threw
        // away perfectly good plans. killer560 (2026-09-23): "it still doesnt insta load the route like it should
        // ... sometimes it does load in the route instantly but most of the time there is delay". His log has the
        // proof: "1 saved plan(s) for this route but none from here (nearest start 0.00 blocks away, v 0.000)" -
        // the very same spot at the very same speed, refused, and searched again from scratch.
        boolean moving = start.speed() > 0.05 || e.startSpeed > 0.05;
        if (moving && start.sprinting != e.sprinting) {
            return Double.NaN;
        }
        return away;
    }

    /**
     * The saved plan for this route, if it has ever been planned. Not "if one was planned from near enough" - if it
     * has one at all.
     * <p>
     * killer560 (2026-09-22): "There is still an issue with it regenerating every time i step back onto a node.
     * Make it so after it loads once it keeps that exact pathing no matter what unless I regenerate it. It should
     * auto load."
     * <p>
     * So: run the same route the same way and it is never searched again. An APPROACH - a place and a speed to
     * arrive from - keeps exactly one plan, and once that plan exists it is never replaced, so the route runs
     * identically every time. {@code /ap3 regenerate} is the only thing that changes it.
     * <p>
     * What it will NOT do is hand back a plan that was built somewhere else. A plan is a fixed schedule of key
     * presses; replayed from the wrong place it does not go to the node, it repeats the movements that went to the
     * node from a different start. On 2026-09-22 a version of this handed one back from 30.19 blocks away, and the
     * route walked sideways off a staircase into lava, drifted, asked again, got the same plan, and looped. So a
     * saved plan is offered only from where it was planned from ({@link #distance}), and arriving somewhere new
     * searches once and saves that approach alongside the others.
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
            // Say WHICH test refused, per entry. "None from here" on its own sent me chasing the wrong field for
            // an evening: the position matched to 0.00 and the speed to 0.000, and the thing actually refusing was
            // a flag. Anything that rejects a saved plan has to name itself.
            StringBuilder why = new StringBuilder();
            for (Entry e : list) {
                double dxyz = Math.sqrt((start.x - e.startX) * (start.x - e.startX)
                        + (start.y - e.startY) * (start.y - e.startY)
                        + (start.z - e.startZ) * (start.z - e.startZ));
                double dv = Math.hypot(start.vx - e.startVx, start.vz - e.startVz);
                if (why.length() > 0) {
                    why.append("; ");
                }
                why.append(String.format(Locale.US, "[%d ticks: %s%s%s%s]", e.ticks,
                        dxyz > START_TOLERANCE
                                ? String.format(Locale.US, "pos %.2f>%.2f ", dxyz, START_TOLERANCE) : "",
                        dv > SPEED_TOLERANCE
                                ? String.format(Locale.US, "vel %.3f>%.3f ", dv, SPEED_TOLERANCE) : "",
                        start.onGround != e.onGround ? "onGround " + e.onGround + "!=" + start.onGround + " " : "",
                        (start.speed() > 0.05 || e.startSpeed > 0.05) && start.sprinting != e.sprinting
                                ? "sprint " + e.sprinting + "!=" + start.sprinting + " " : ""));
            }
            LOGGER.info("[AP3 route] {} saved plan(s) for this route, none usable from here (at {} v {} onGround {}"
                            + " sprint {}) - searching. Refused: {}", list.size(),
                    String.format(Locale.US, "%.2f,%.2f,%.2f", start.x, start.y, start.z),
                    String.format(Locale.US, "%.3f", start.speed()), start.onGround, start.sprinting, why);
            return null;
        }
        LOGGER.info("[AP3 route] using a saved {}-tick plan ({} blocks from where it was planned, {} approach(es)"
                + " known for this route)", best.ticks, String.format(Locale.US, "%.2f", bestAway), list.size());
        Ap3RoutePlanner.Plan p = new Ap3RoutePlanner.Plan();
        p.steps = best.steps.toArray(new Ap3RoutePlanner.Step[0]);
        p.ticks = p.steps.length;
        p.complete = true;
        p.note = "saved";
        p.gateTick = new int[0];
        return p;
    }

    /**
     * Keep this plan as the answer for the approach it was planned from, if that approach has no answer yet.
     * <p>
     * Once an approach has a plan it is never replaced, not even by a shorter one: that is what makes the route run
     * identically every time, which is what he asked for ("after it loads once it keeps that exact pathing no
     * matter what unless I regenerate it"). New approaches are still added alongside, because a schedule of keys is
     * only valid from the state it was built for - see {@link #lookup}.
     */
    static void offer(String signature, Ap3RouteMath.RouteState start, Ap3RoutePlanner.Plan plan) {
        if (plan == null || !plan.complete || plan.steps.length == 0 || signature.isEmpty()) {
            return;
        }
        load();
        List<Entry> list = ENTRIES.computeIfAbsent(signature, k -> new java.util.concurrent.CopyOnWriteArrayList<>());
        for (Entry candidate : list) {
            if (!Double.isNaN(distance(candidate, start))) {
                return; // this approach is already settled
            }
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
        list.add(e);
        while (list.size() > MAX_PER_ROUTE) {
            list.remove(0);
        }
        LOGGER.info("[AP3 route] saved a {}-tick plan for this approach - {} approach(es) known for this route,"
                + " each of which now runs exactly the same every time", plan.ticks, list.size());
        save();
    }

    /**
     * Forget the saved plans for the route whose Path nodes are nearest the player, and return how many went. -1
     * when there is no Path node near enough to mean anything.
     * <p>
     * The route is identified the same way it always is - by its nodes - so this finds the Path chain he is
     * standing in and drops just that one, leaving every other route's work alone.
     */
    static int forgetNearest() {
        load();
        Ap3Chain chain = Ap3Feature.currentChain();
        net.minecraft.client.player.LocalPlayer player = Minecraft.getInstance().player;
        if (chain == null || player == null) {
            return -1;
        }
        List<Ap3Node> path = new ArrayList<>();
        for (Ap3Node n : chain.nodes()) {
            if (n.type == Ap3Node.Type.PATH) {
                path.add(n);
            }
        }
        if (path.isEmpty()) {
            return -1;
        }
        double nearest = Double.MAX_VALUE;
        for (Ap3Node n : path) {
            nearest = Math.min(nearest, Math.hypot(n.x - player.getX(), n.z - player.getZ()));
        }
        if (nearest > NEAR_ENOUGH) {
            return -1;
        }
        path.sort(java.util.Comparator.comparingInt(a -> a.pathIndex));
        int dropped = 0;
        // A leg is any run of Path nodes, and each leg has its own signature, so drop every signature that this
        // chain's nodes can make rather than trying to guess which leg he means.
        for (int from = 0; from < path.size(); from++) {
            for (int to = from + 1; to <= path.size(); to++) {
                List<Ap3Node> leg = path.subList(from, to);
                List<Entry> gone = ENTRIES.remove(signature(leg));
                if (gone != null) {
                    dropped += gone.size();
                }
            }
        }
        if (dropped > 0) {
            save();
        }
        LOGGER.info("[AP3 route] regenerate: dropped {} saved plan(s) for the route near ({}, {})", dropped,
                String.format(Locale.US, "%.1f", player.getX()), String.format(Locale.US, "%.1f", player.getZ()));
        return dropped;
    }

    /** How close a Path node has to be for /ap3 regenerate to mean that route. */
    private static final double NEAR_ENOUGH = 12.0;

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
                                st.get("yaw").getAsFloat(), st.get("j").getAsBoolean(),
                                !st.has("sp") || st.get("sp").getAsBoolean()));
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
                    s.addProperty("sp", st.sprint());
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
