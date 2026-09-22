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

    static void write(Ap3RouteMath.RouteState start, List<Ap3RoutePlanner.Gate> gates,
                      List<Ap3RoutePlanner.Blocked> blocked, Ap3RouteRunner.Snap snap,
                      Ap3DiscretePlanner.Model m) {
        try {
            JsonObject root = new JsonObject();

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

            Path f = Minecraft.getInstance().gameDirectory.toPath()
                    .resolve("config").resolve("killer560smod").resolve("ap3-route-dump.json");
            Files.createDirectories(f.getParent());
            Files.writeString(f, root.toString(), StandardCharsets.UTF_8);
            LOGGER.info("[AP3 route] dumped {} boxes and {} gates to {}", boxes.size(), gates.size(), f);
        } catch (Throwable t) {
            LOGGER.warn("[AP3 route] could not write the dump", t);
        }
    }
}
