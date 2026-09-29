package com.killer560.hub.roomsim;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.livemap.DungeonLayout;
import com.killer560.hub.roomdatabase.RoomDatabase;
import com.killer560.hub.roomdatabase.RoomEntry;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.ModChat;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Records how big a real floor actually is, so the sim's generator can stop guessing.
 *
 * <p>killer560 (2026-09-28): "i can load in a full run of an entrance through f7 as those will be the only map
 * sizes so long as you can log the overall size and what floor it is."
 *
 * <p>{@link SimFloorGen}'s per-floor room counts are estimates I wrote down because this mod had nothing better
 * - and an estimate in a generator is worse than it looks, because every floor it produces is subtly the wrong
 * shape and nothing about it announces that. One real run per floor replaces the whole table with measurements.
 *
 * <p>It records the things the generator actually needs and nothing else: how many rooms, how far across the
 * grid they reach, how many puzzles, and how many rooms stand between the entrance and the blood door. That
 * last one is the number behind his 2-8 slider, and it is the one I had least basis for.
 *
 * <p>Samples accumulate per floor rather than overwriting, because floors vary between runs - one F7 is a data
 * point, not the answer, and a range is the honest output.
 */
public final class FloorSizeLog {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-roomsim");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final Path FILE =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-floor-sizes.json");

    /** One sample per dungeon, so re-entering the same run does not count twice. */
    private static String lastSampledKey;

    /** Ticks between checks. The layout fills in as the map is revealed, so there is no point looking often. */
    private static final int CHECK_INTERVAL = 40;

    private static int tickCounter;

    private FloorSizeLog() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (++tickCounter % CHECK_INTERVAL != 0) {
                return;
            }
            try {
                sample(client);
            } catch (Throwable t) {
                LOGGER.warn("Floor size sample failed", t);
            }
        });
    }

    private static void sample(Minecraft client) {
        if (client.level == null || !DungeonState.isInDungeon()) {
            return;
        }
        DungeonLayout layout = DungeonLayout.current();
        if (layout == null || layout.roomCount() <= 0) {
            return;
        }
        String floor = DungeonState.getFloor();
        if (floor == null || floor.isBlank()) {
            return;
        }

        // Which grid cells hold a room, and which room each belongs to.
        Set<Integer> roomCells = new HashSet<>();
        Map<Integer, Integer> cellToRoom = new HashMap<>();
        int minGx = Integer.MAX_VALUE;
        int minGz = Integer.MAX_VALUE;
        int maxGx = Integer.MIN_VALUE;
        int maxGz = Integer.MIN_VALUE;
        for (int cell = 0; cell < DungeonLayout.GRID * DungeonLayout.GRID; cell++) {
            int room = layout.roomOfCell(cell);
            if (room < 0) {
                continue;
            }
            roomCells.add(cell);
            cellToRoom.put(cell, room);
            int gx = cell % DungeonLayout.GRID;
            int gz = cell / DungeonLayout.GRID;
            minGx = Math.min(minGx, gx);
            minGz = Math.min(minGz, gz);
            maxGx = Math.max(maxGx, gx);
            maxGz = Math.max(maxGz, gz);
        }
        if (roomCells.isEmpty()) {
            return;
        }

        // Distinct rooms, not cells: a 2x2 room covers four cells and is still one room.
        Set<Integer> rooms = new HashSet<>(cellToRoom.values());

        int puzzles = 0;
        int traps = 0;
        Integer entranceRoom = null;
        Integer bloodRoom = null;
        for (int room : rooms) {
            RoomEntry entry = RoomDatabase.lookupByName(layout.name(room));
            String type = entry == null || entry.type == null ? "" : entry.type.toUpperCase(Locale.ROOT);
            switch (type) {
                case "PUZZLE" -> puzzles++;
                case "TRAP" -> traps++;
                case "ENTRANCE" -> entranceRoom = room;
                case "BLOOD" -> bloodRoom = room;
                default -> { }
            }
        }

        int cellsToBlood = entranceRoom == null || bloodRoom == null
                ? -1 : distanceBetween(layout, cellToRoom, entranceRoom, bloodRoom);

        // Only once the map looks fully revealed. Sampling a half-discovered floor would record a smaller
        // dungeon than the one he actually ran, and a table built from those would be wrong in the direction
        // that looks plausible.
        if (entranceRoom == null || bloodRoom == null) {
            return;
        }

        String key = floor + ":" + rooms.size() + ":" + cellsToBlood;
        if (key.equals(lastSampledKey)) {
            return;
        }
        lastSampledKey = key;

        JsonObject sample = new JsonObject();
        sample.addProperty("floor", floor);
        sample.addProperty("rooms", rooms.size());
        sample.addProperty("gridWidth", (maxGx - minGx) / 2 + 1);
        sample.addProperty("gridHeight", (maxGz - minGz) / 2 + 1);
        sample.addProperty("puzzles", puzzles);
        sample.addProperty("traps", traps);
        sample.addProperty("cellsToBlood", cellsToBlood);
        sample.addProperty("at", System.currentTimeMillis());
        append(sample);

        LOGGER.info("[FloorSize] {}: {} rooms, {}x{} cells, {} puzzle(s), {} trap(s), {} cells to blood",
                floor, rooms.size(), (maxGx - minGx) / 2 + 1, (maxGz - minGz) / 2 + 1,
                puzzles, traps, cellsToBlood);
        ModChat.send("Floor Size", ModChat.text(floor + ": "), ModChat.value(String.valueOf(rooms.size())),
                ModChat.text(" rooms, "), ModChat.value(String.valueOf(puzzles)),
                ModChat.text(" puzzles, blood "), ModChat.value(String.valueOf(cellsToBlood)),
                ModChat.text(" cells in"));
    }

    /**
     * Grid CELLS from the entrance to the blood door.
     *
     * <p>killer560 (2026-09-28): "it shouldd be in cells not rooms." I had it counting rooms, so a 2x2 in the
     * way cost one step. Cells is the right unit and the correction matters: a 2x2 is two cells of walking
     * whichever way you cross it, and the number is meant to describe how far the blood door is, not how many
     * room names you pass on the way.
     *
     * <p>So this walks cells, stepping between adjacent room cells only where the cell between them is a door.
     * Cells of the same room connect freely - crossing a 2x2 is real distance, but it needs no door.
     */
    private static int distanceBetween(DungeonLayout layout, Map<Integer, Integer> cellToRoom,
                                       int fromRoom, int toRoom) {
        Map<Integer, Integer> dist = new HashMap<>();
        Deque<Integer> queue = new ArrayDeque<>();
        // Start from every cell of the entrance at distance zero: the room itself costs nothing to be in.
        for (Map.Entry<Integer, Integer> e : cellToRoom.entrySet()) {
            if (e.getValue() == fromRoom) {
                dist.put(e.getKey(), 0);
                queue.add(e.getKey());
            }
        }
        while (!queue.isEmpty()) {
            int cell = queue.poll();
            if (cellToRoom.get(cell) == toRoom) {
                return dist.get(cell);
            }
            int gx = cell % DungeonLayout.GRID;
            int gz = cell / DungeonLayout.GRID;
            for (int[] step : new int[][]{{2, 0}, {-2, 0}, {0, 2}, {0, -2}}) {
                int nx = gx + step[0];
                int nz = gz + step[1];
                if (nx < 0 || nz < 0 || nx >= DungeonLayout.GRID || nz >= DungeonLayout.GRID) {
                    continue;
                }
                int next = nz * DungeonLayout.GRID + nx;
                Integer otherRoom = cellToRoom.get(next);
                if (otherRoom == null || dist.containsKey(next)) {
                    continue;
                }
                // Within one room there is no door to pass; between two rooms there has to be one, or they
                // merely sit side by side with a wall between them.
                boolean sameRoom = otherRoom.equals(cellToRoom.get(cell));
                int between = (gz + step[1] / 2) * DungeonLayout.GRID + (gx + step[0] / 2);
                if (!sameRoom && !layout.isDoor(between)) {
                    continue;
                }
                dist.put(next, dist.get(cell) + 1);
                queue.add(next);
            }
        }
        return -1;
    }

    private static synchronized void append(JsonObject sample) {
        try {
            JsonArray all = new JsonArray();
            if (Files.exists(FILE)) {
                all = JsonParser.parseString(Files.readString(FILE, StandardCharsets.UTF_8)).getAsJsonArray();
            }
            all.add(sample);
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, GSON.toJson(all), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.warn("Could not write the floor size log", e);
        }
    }

    /** What has been recorded so far, per floor - for chat, and for me to read off disk. */
    public static String summary() {
        try {
            if (!Files.exists(FILE)) {
                return "No floors recorded yet.";
            }
            JsonArray all = JsonParser.parseString(
                    Files.readString(FILE, StandardCharsets.UTF_8)).getAsJsonArray();
            Map<String, List<Integer>> byFloor = new HashMap<>();
            for (var el : all) {
                JsonObject o = el.getAsJsonObject();
                byFloor.computeIfAbsent(o.get("floor").getAsString(), k -> new ArrayList<>())
                        .add(o.get("rooms").getAsInt());
            }
            StringBuilder sb = new StringBuilder();
            for (var e : byFloor.entrySet()) {
                int min = e.getValue().stream().mapToInt(Integer::intValue).min().orElse(0);
                int max = e.getValue().stream().mapToInt(Integer::intValue).max().orElse(0);
                sb.append(e.getKey()).append(' ').append(min == max ? String.valueOf(min) : (min + "-" + max))
                        .append(" rooms (").append(e.getValue().size()).append(" run(s));  ");
            }
            return sb.isEmpty() ? "No floors recorded yet." : sb.toString();
        } catch (Exception e) {
            return "Could not read the floor size log.";
        }
    }
}
