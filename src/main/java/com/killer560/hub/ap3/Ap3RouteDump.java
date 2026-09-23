package com.killer560.hub.ap3;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Writes out everything one plan was given - where he was, what the nodes ask for, and every collision box the
 * snapshot read - so the exact same problem can be replayed away from the game.
 * <p>
 * Built on 2026-09-22 after several rounds of rebuilding killer560's geometry from descriptions and getting it
 * wrong each time: the offline course always worked and his never did. Guessing at the world is the slow way to
 * fix a pathfinder; having the world is the fast one.
 */
final class Ap3RouteDump {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-ap3-route");

    private Ap3RouteDump() {
    }

    /** The last few failures, kept side by side so one bad route does not overwrite another. */
    /** What the four kept captures are OF, so a failure that repeats does not evict the others. */
    private static final String[] recent = new String[4];
    /** How many captures each route has produced, so each keeps its own pair of slots. */
    private static final java.util.Map<String, Integer> perRoute = new java.util.HashMap<>();

    private static int slot;
    private static long lastWrite;

    /**
     * Write a dump because a plan could not be finished - automatically, at most one every few seconds, rotating
     * through a handful of files. killer560 offered to "take a scan of the room" by hand; this saves him the job,
     * and it captures the failure at the moment it happens rather than the next time he can be bothered.
     */
    static void writeFailure(Ap3RouteMath.RouteState start, List<Ap3RoutePlanner.Gate> gates,
                             List<Ap3RoutePlanner.Blocked> blocked, Ap3RouteRunner.Snap snap,
                             Ap3DiscretePlanner.Model m, String why) {
        long now = System.currentTimeMillis();
        if (now - lastWrite < 4000) {
            return;
        }
        // One failure that keeps happening must not cost us the other three captures. On 2026-09-23 he fell into a
        // pit and the route retried from down there over and over; each retry took a slot, and within a minute all
        // four held the same unsolvable attempt - the capture of the failure actually worth looking at was gone.
        // Same start, same nodes, same reason: it is the same failure, and one copy of it is enough.
        String fingerprint = String.format(Locale.US, "%.2f,%.2f,%.2f|%d|%s", start.x, start.y, start.z,
                gates.size(), why);
        for (String seen : recent) {
            if (fingerprint.equals(seen)) {
                return;
            }
        }
        recent[slot % recent.length] = fingerprint;
        lastWrite = now;
        // Named after the ROUTE - its last node - rather than a global slot, so one section's failures cannot
        // evict another's. On 2026-09-23 four captures of his storm route were overwritten within eight minutes
        // by his s3 testing, and storm was the one that needed looking at. Two slots each is enough to see a
        // pattern, and a route is identified by where it ends, which is what makes it that route.
        Ap3RoutePlanner.Gate last = gates.isEmpty() ? null : gates.get(gates.size() - 1);
        String where = last == null ? "unknown"
                : String.format(Locale.US, "%d_%d_%d", Math.round(last.x), Math.round(last.y), Math.round(last.z));
        int turn = perRoute.merge(where, 1, Integer::sum) % 2;
        slot++;
        write(start, gates, blocked, snap, m, "ap3-route-failure-" + where + "-" + turn + ".json", why);
    }

    static void write(Ap3RouteMath.RouteState start, List<Ap3RoutePlanner.Gate> gates,
                      List<Ap3RoutePlanner.Blocked> blocked, Ap3RouteRunner.Snap snap,
                      Ap3DiscretePlanner.Model m) {
        write(start, gates, blocked, snap, m, "ap3-route-dump.json", "");
    }

    static void write(Ap3RouteMath.RouteState start, List<Ap3RoutePlanner.Gate> gates,
                      List<Ap3RoutePlanner.Blocked> blocked, Ap3RouteRunner.Snap snap,
                      Ap3DiscretePlanner.Model m, String name, String why) {
        try {
            JsonObject root = new JsonObject();
            root.addProperty("why", why);

            JsonObject st = new JsonObject();
            st.addProperty("x", start.x);
            st.addProperty("y", start.y);
            st.addProperty("z", start.z);
            st.addProperty("vx", start.vx);
            st.addProperty("vy", start.vy);
            st.addProperty("vz", start.vz);
            st.addProperty("yaw", start.yaw);
            st.addProperty("onGround", start.onGround);
            st.addProperty("sprinting", start.sprinting);
            st.addProperty("crouching", start.crouching);
            st.addProperty("sprintBlocked", start.sprintBlocked);
            root.add("start", st);

            JsonObject model = new JsonObject();
            model.addProperty("baseSpeedAttr", m.baseSpeedAttr);
            model.addProperty("blockFriction", m.blockFriction);
            model.addProperty("sneakMul", m.sneakMul);
            model.addProperty("sprintKeyHeld", m.sprintKeyHeld);
            root.add("model", model);

            JsonArray gs = new JsonArray();
            for (Ap3RoutePlanner.Gate g : gates) {
                JsonObject o = new JsonObject();
                o.addProperty("x", g.x);
                o.addProperty("y", g.y);
                o.addProperty("z", g.z);
                o.addProperty("halfW", g.halfW);
                o.addProperty("halfL", g.halfL);
                o.addProperty("group", g.group);
                o.addProperty("exact", g.exact);
                o.addProperty("mustLand", g.mustLand);
                o.addProperty("minSpeed", g.minSpeed);
                o.addProperty("maxSpeed", g.maxSpeed);
                gs.add(o);
            }
            root.add("gates", gs);

            JsonArray bs = new JsonArray();
            for (Ap3RoutePlanner.Blocked b : blocked) {
                JsonObject o = new JsonObject();
                o.addProperty("minX", b.minX);
                o.addProperty("minY", b.minY);
                o.addProperty("minZ", b.minZ);
                o.addProperty("maxX", b.maxX);
                o.addProperty("maxY", b.maxY);
                o.addProperty("maxZ", b.maxZ);
                bs.add(o);
            }
            root.add("blocked", bs);

            JsonArray boxes = new JsonArray();
            for (Ap3RouteCollide.Box b : snap.boxWorld().boxes()) {
                JsonArray a = new JsonArray();
                a.add(b.minX);
                a.add(b.minY);
                a.add(b.minZ);
                a.add(b.maxX);
                a.add(b.maxY);
                a.add(b.maxZ);
                boxes.add(a);
            }
            root.add("boxes", boxes);

            // The coarse surface grid the planner actually planned against - see Snap.surfaceGrid().
            double[] grid = snap.surfaceGrid();
            JsonArray gr = new JsonArray();
            for (double v : grid) {
                gr.add(Double.isNaN(v) ? null : Double.valueOf(v));
            }
            root.add("surface", gr);

            Path f = Minecraft.getInstance().gameDirectory.toPath()
                    .resolve("config").resolve("killer560smod").resolve(name);
            Files.createDirectories(f.getParent());
            Files.writeString(f, root.toString(), StandardCharsets.UTF_8);
            LOGGER.info("[AP3 route] dumped {} boxes and {} gates to {}", boxes.size(), gates.size(), f.getFileName());
        } catch (Throwable t) {
            LOGGER.warn("[AP3 route] could not write the dump", t);
        }
    }
}
