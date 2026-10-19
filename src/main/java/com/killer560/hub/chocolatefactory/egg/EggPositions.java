package com.killer560.hub.chocolatefactory.egg;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.chocolatefactory.egg.HoppityCalendar.Meal;
import com.killer560.hub.pathfinding.IslandDetector;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.ModPaths;
import com.killer560.hub.util.SkyblockGate;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The egg spots this client has actually seen, per island (killer560, 2026-10-19: "snap to the right points, those are the
 * only ones it can be at"). An egg is an invisible, unnamed armour stand wearing an egg head ({@link EggEsp#mealOf}); each
 * loaded one is written here the first time it is seen, and the file keeps them between sessions. The Egglocator's trail
 * guess is snapped to the nearest of these within {@link #MAX_SNAP} blocks (SkyHanni's MAX_GUESS_DISTANCE of 5), so the
 * marker lands on a real egg rather than on a particle.
 * <p>
 * Limit: this is only as complete as what has been loaded. An egg never seen on that island is not in the set, so a trail
 * to it does not snap and nothing is drawn. Read-only: it reads entities already loaded, sends nothing.
 */
public final class EggPositions {

    private static final Logger LOGGER = ModLog.get("killer560smod-chocolatefactory");
    private static final Path PATH = ModPaths.config("killer560smod-eggpositions.json");

    /** The Egglocator's guess is snapped only to an egg this close (SkyHanni MAX_GUESS_DISTANCE). */
    static final double MAX_SNAP = 5.0;
    /** Two stands of one meal this close on one island are the same egg. */
    private static final double SAME_EGG = 0.75;
    /** Head height of a full-size armour stand above its feet: the point the snap lands on. */
    private static final double HEAD_Y = 1.7;
    private static final int SCAN_EVERY_TICKS = 10;

    /** One egg spot. Stored at the stand's feet; {@link #point} is the head height the snap uses. */
    record Spot(double x, double y, double z, Meal meal) {
        Vec3 point() {
            return new Vec3(x, y + HEAD_Y, z);
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<String, List<Spot>> ISLANDS = new HashMap<>();
    private static boolean loaded;
    private static int ticks;

    private EggPositions() {
    }

    /** The island the player is on, or null when unknown. */
    private static String island() {
        String graph = IslandDetector.graphIsland();
        if (graph != null && !graph.isEmpty()) {
            return graph;
        }
        String name = IslandDetector.islandName();
        return name == null || name.isEmpty() ? null : name;
    }

    /** Client tick: every few ticks, records each egg stand loaded near the player. */
    static void tick(Minecraft client) {
        try {
            if (client.level == null || client.player == null || !SkyblockGate.allows()) {
                return;
            }
            if (++ticks % SCAN_EVERY_TICKS != 0) {
                return;
            }
            String island = island();
            if (island == null) {
                return;
            }
            for (Entity e : client.level.entitiesForRendering()) {
                if (!(e instanceof ArmorStand stand) || !stand.isInvisible() || stand.hasCustomName()) {
                    continue;
                }
                Meal meal = EggEsp.mealOf(stand.getItemBySlot(EquipmentSlot.HEAD));
                if (meal != null) {
                    record(island, new Spot(stand.getX(), stand.getY(), stand.getZ(), meal));
                }
            }
        } catch (RuntimeException e) {
            LOGGER.error("[EggPositions] scan failed", e);
        }
    }

    /** Adds {@code spot} to {@code island} unless that egg is already known. Saves only when something new was added. */
    static synchronized void record(String island, Spot spot) {
        ensureLoaded();
        List<Spot> list = ISLANDS.computeIfAbsent(island, k -> new ArrayList<>());
        for (Spot s : list) {
            if (s.meal() == spot.meal() && distance(s.point(), spot.point()) < SAME_EGG) {
                return;
            }
        }
        list.add(spot);
        save();
    }

    /**
     * The known egg nearest to {@code guess} on the current island, as its head point, when it is within {@link #MAX_SNAP};
     * null when none is that close (or the island is unknown).
     */
    static synchronized Vec3 snap(Vec3 guess) {
        String island = island();
        if (island == null) {
            return null;
        }
        ensureLoaded();
        List<Spot> list = ISLANDS.get(island);
        if (list == null) {
            return null;
        }
        Vec3 best = null;
        double bestDist = MAX_SNAP;
        for (Spot s : list) {
            double d = distance(s.point(), guess);
            if (d <= bestDist) {
                bestDist = d;
                best = s.point();
            }
        }
        return best;
    }

    private static double distance(Vec3 a, Vec3 b) {
        return a.distanceTo(b);
    }

    private static synchronized void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        if (!Files.exists(PATH)) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(PATH, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject islands = root.getAsJsonObject("islands");
            if (islands == null) {
                return;
            }
            for (Map.Entry<String, JsonElement> entry : islands.entrySet()) {
                List<Spot> list = new ArrayList<>();
                for (JsonElement el : entry.getValue().getAsJsonArray()) {
                    JsonObject o = el.getAsJsonObject();
                    list.add(new Spot(o.get("x").getAsDouble(), o.get("y").getAsDouble(), o.get("z").getAsDouble(),
                            Meal.valueOf(o.get("meal").getAsString())));
                }
                ISLANDS.put(entry.getKey(), list);
            }
        } catch (Exception e) {
            ISLANDS.clear();
            LOGGER.error("[EggPositions] could not read " + PATH, e);
        }
    }

    private static synchronized void save() {
        try {
            Files.createDirectories(PATH.getParent());
            JsonObject islands = new JsonObject();
            for (Map.Entry<String, List<Spot>> entry : ISLANDS.entrySet()) {
                JsonArray arr = new JsonArray();
                for (Spot s : entry.getValue()) {
                    JsonObject o = new JsonObject();
                    o.addProperty("x", s.x());
                    o.addProperty("y", s.y());
                    o.addProperty("z", s.z());
                    o.addProperty("meal", s.meal().name());
                    arr.add(o);
                }
                islands.add(entry.getKey(), arr);
            }
            JsonObject root = new JsonObject();
            root.add("islands", islands);
            Files.writeString(PATH, GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.error("[EggPositions] could not write " + PATH, e);
        }
    }
}
