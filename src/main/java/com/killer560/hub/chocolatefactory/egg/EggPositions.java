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

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The egg spots for each island (killer560, 2026-10-19: "make their current egg table saved, then if they ever find an egg
 * at a non saved spot add that spot"). Two sources, both read at startup:
 * <ul>
 * <li>{@code assets/killer560smod/egg_locations.json}: the spots SkyHanni's HoppityEggLocations table lists per island
 * (SkyHanni-REPO, MIT, Copyright (c) 2022 hannibal2). Converted to {@code [x,y,z]} per island; no meal, since the table
 * does not give one.</li>
 * <li>{@code killer560smod-eggpositions.json}: every egg armour stand this client has seen loaded (an invisible, unnamed
 * armour stand wearing an egg head, {@link EggEsp#mealOf}). A spot not already in the set is added and the file saved.</li>
 * </ul>
 * The Egglocator's trail guess is snapped to the nearest spot within {@link #MAX_SNAP} blocks (SkyHanni's
 * MAX_GUESS_DISTANCE of 5). Read-only: it reads entities already loaded, sends nothing.
 */
public final class EggPositions {

    private static final Logger LOGGER = ModLog.get("killer560smod-chocolatefactory");
    private static final Path PATH = ModPaths.config("killer560smod-eggpositions.json");
    private static final String BUNDLED = "/assets/killer560smod/egg_locations.json";

    /** The Egglocator's guess is snapped only to an egg this close (SkyHanni MAX_GUESS_DISTANCE). */
    static final double MAX_SNAP = 5.0;
    /** Two stands of one meal this close on one island are the same egg. */
    private static final double SAME_EGG = 0.75;
    /** Head height of a full-size armour stand above its feet. The table's spots are head points, so they are stored at feet. */
    private static final double HEAD_Y = 1.7;
    private static final int SCAN_EVERY_TICKS = 10;

    /** One egg spot, stored at the stand's feet. {@code meal} is null for a spot from the bundled table. */
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

    /** Adds {@code spot} to {@code island} unless that egg is already in the set. Saves only when something new was added. */
    static synchronized void record(String island, Spot spot) {
        ensureLoaded();
        if (add(island, spot)) {
            save();
        }
    }

    /** Adds {@code spot} unless a spot of the same meal (or any meal, when either has none) is this close. */
    private static boolean add(String island, Spot spot) {
        List<Spot> list = ISLANDS.computeIfAbsent(island, k -> new ArrayList<>());
        for (Spot s : list) {
            boolean sameMeal = s.meal() == null || spot.meal() == null || s.meal() == spot.meal();
            if (sameMeal && s.point().distanceTo(spot.point()) < SAME_EGG) {
                return false;
            }
        }
        list.add(spot);
        return true;
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
            double d = s.point().distanceTo(guess);
            if (d <= bestDist) {
                bestDist = d;
                best = s.point();
            }
        }
        return best;
    }

    private static synchronized void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        loadBundled();
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
                for (JsonElement el : entry.getValue().getAsJsonArray()) {
                    JsonObject o = el.getAsJsonObject();
                    JsonElement meal = o.get("meal");
                    Meal m = meal == null ? null : Meal.valueOf(meal.getAsString());
                    add(entry.getKey(), new Spot(o.get("x").getAsDouble(), o.get("y").getAsDouble(),
                            o.get("z").getAsDouble(), m));
                }
            }
        } catch (Exception e) {
            LOGGER.error("[EggPositions] could not read " + PATH, e);
        }
    }

    /** The bundled table: {@code {island: [[x,y,z], ...]}} with head points, stored at feet. */
    private static void loadBundled() {
        try (InputStream in = EggPositions.class.getResourceAsStream(BUNDLED)) {
            if (in == null) {
                LOGGER.error("[EggPositions] bundled egg table missing: " + BUNDLED);
                return;
            }
            JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                for (JsonElement el : entry.getValue().getAsJsonArray()) {
                    JsonArray p = el.getAsJsonArray();
                    add(entry.getKey(), new Spot(p.get(0).getAsDouble(), p.get(1).getAsDouble() - HEAD_Y,
                            p.get(2).getAsDouble(), null));
                }
            }
        } catch (Exception e) {
            LOGGER.error("[EggPositions] could not read bundled egg table", e);
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
                    if (s.meal() != null) {
                        o.addProperty("meal", s.meal().name());
                    }
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
