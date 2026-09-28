package com.killer560.hub.roomsim;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.livemap.DungeonLayout;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The captured rooms: their blocks, and how much of each one has actually been seen.
 *
 * <p>This exists because no public dungeon dataset ships room GEOMETRY. The well-known references store secret
 * coordinates and a way to tell which room you are standing in, which is all a waypoint mod needs and is not
 * enough to build a room you can walk through (checked 2026-09-28). So the blocks have to come from real runs,
 * and this is where they land. Once a room is complete it never needs capturing again, and the finished library
 * ships inside the mod.
 *
 * <p><b>Completeness is per column, not per room.</b> A room is only ever partly loaded - you see the half you
 * walked through and the rest is outside render distance or behind a wall - so "captured" cannot be a flag set
 * the first time a room is entered. Each room records which of its 31x31-per-tile columns have been read, and is
 * only finished when all of them have. That is what lets the recorder know which rooms it still needs, rather
 * than believing it has a room it has only seen a corner of.
 */
public final class RoomLibrary {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-roomsim");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path DIR =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-rooms");

    /** One dungeon tile is 31 blocks across with a 1-block seam; the grid steps every 32. */
    public static final int TILE = 31;

    /**
     * Vertical slice kept per room.
     *
     * <p>Dungeon floors sit at y 69 and the tallest rooms do not reach y 140. Storing the whole world column
     * would multiply the library for nothing, and cutting it too close silently loses ceilings, which is the
     * kind of loss you only notice once the room is rebuilt and has a hole in it.
     */
    public static final int MIN_Y = 60;
    public static final int MAX_Y = 140;

    /** name -> room. A TreeMap so the "still needed" list is stable and alphabetical. */
    private static final Map<String, Room> ROOMS = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private static boolean loaded;

    private RoomLibrary() {
    }

    /** One captured room. Blocks are a palette plus an index per position, which is what makes this small. */
    public static final class Room {
        public final String name;
        /** Width and depth in blocks, from how many tiles the room occupies. */
        public int sizeX;
        public int sizeZ;
        /** Palette index per (x, y, z); -1 means never read. */
        public short[] blocks;
        public final List<String> palette = new ArrayList<>();
        private final Map<String, Short> paletteIndex = new LinkedHashMap<>();
        /** Which columns have been read at least once - this is what completeness means. */
        public boolean[] seenColumn;

        /**
         * Where starred mobs stood, in ROOM-LOCAL coordinates.
         *
         * <p>Captured as well as the blocks because a sim room without its mobs is scenery. Hypixel puts the
         * "star" name on a separate invisible armour stand rather than on the mob, so these are the stands'
         * positions - which is where the mob is, and is the only thing visible from the client.
         *
         * <p>Deduplicated by position rather than accumulated: a room walked through five times would otherwise
         * hold five copies of the same spawn, and the sim would spawn five mobs where Hypixel spawns one.
         */
        public final java.util.Set<String> mobSpawns = new java.util.LinkedHashSet<>();

        Room(String name, int sizeX, int sizeZ) {
            this.name = name;
            this.sizeX = sizeX;
            this.sizeZ = sizeZ;
            this.blocks = new short[sizeX * sizeZ * (MAX_Y - MIN_Y + 1)];
            java.util.Arrays.fill(this.blocks, (short) -1);
            this.seenColumn = new boolean[sizeX * sizeZ];
        }

        short paletteFor(String state) {
            Short existing = paletteIndex.get(state);
            if (existing != null) {
                return existing;
            }
            short id = (short) palette.size();
            palette.add(state);
            paletteIndex.put(state, id);
            return id;
        }

        int index(int x, int y, int z) {
            return (y - MIN_Y) * sizeX * sizeZ + z * sizeX + x;
        }

        /**
         * Writes one block, for rooms built in code rather than captured.
         *
         * <p>Also marks the column read, because a synthetic room is complete by construction - there is no
         * "rest of it" still out of render distance.
         */
        void set(int x, int y, int z, String blockId) {
            if (x < 0 || z < 0 || x >= sizeX || z >= sizeZ || y < MIN_Y || y > MAX_Y) {
                return;
            }
            blocks[index(x, y, z)] = paletteFor(blockId);
            seenColumn[z * sizeX + x] = true;
        }

        /** Marks every column read. For synthetic rooms; a captured one earns this a column at a time. */
        void markComplete() {
            java.util.Arrays.fill(seenColumn, true);
            for (int i = 0; i < blocks.length; i++) {
                if (blocks[i] == -1) {
                    blocks[i] = paletteFor("minecraft:air");
                }
            }
        }

        /** How much of the room has been read, 0 to 1. */
        public double completeness() {
            int seen = 0;
            for (boolean b : seenColumn) {
                if (b) {
                    seen++;
                }
            }
            return seenColumn.length == 0 ? 0 : (double) seen / seenColumn.length;
        }

        public boolean complete() {
            return completeness() >= 0.999;
        }
    }

    public static synchronized void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        try {
            if (!Files.isDirectory(DIR)) {
                return;
            }
            try (var files = Files.list(DIR)) {
                for (Path f : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                    try {
                        Room r = fromJson(JsonParser.parseString(
                                Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject());
                        if (r != null) {
                            ROOMS.put(r.name, r);
                        }
                    } catch (Exception e) {
                        LOGGER.warn("Could not read captured room {}", f.getFileName(), e);
                    }
                }
            }
            LOGGER.info("Room library: {} room(s) on disk, {} complete", ROOMS.size(), completeCount());
        } catch (Exception e) {
            LOGGER.error("Could not load the room library", e);
        }
    }

    /**
     * Rooms built in code, kept apart from captured ones.
     *
     * <p>A SEPARATE map on purpose (killer560, 2026-09-28: "make sure it's all in a spot that can easily be
     * deleted and won't accidentally contaminate other parts of the mod"). If a synthetic room shared the real
     * map it would be written to disk by {@link #saveAll}, counted in his capture progress, and listed as a room
     * still needing work - it would end up in the shipped library looking exactly like a real one. Nothing in
     * here is ever saved, counted, or reported as missing.
     */
    private static final Map<String, Room> TEST_ROOMS = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

    /** Builds an empty SYNTHETIC room. Never persisted; see {@link #TEST_ROOMS}. */
    static synchronized Room createTestRoom(String name, int sizeX, int sizeZ) {
        Room r = new Room(name, sizeX, sizeZ);
        TEST_ROOMS.put(name, r);
        return r;
    }

    /** Forgets every synthetic room. One call, for when the real scanning starts. */
    public static synchronized void clearTestRooms() {
        TEST_ROOMS.clear();
    }

    /** Whether a room of this name is known, captured or synthetic. */
    public static synchronized boolean has(String name) {
        load();
        return ROOMS.containsKey(name) || TEST_ROOMS.containsKey(name);
    }

    /**
     * One room by name, or null.
     *
     * <p>Captured rooms win over synthetic ones, so the moment a real room of that name is scanned the test
     * copy stops being used rather than shadowing it.
     */
    public static synchronized Room get(String name) {
        load();
        Room real = ROOMS.get(name);
        return real != null ? real : TEST_ROOMS.get(name);
    }

    /** Every room name known, captured or synthetic, for the picker. */
    public static synchronized java.util.List<String> names() {
        load();
        java.util.Set<String> all = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        all.addAll(ROOMS.keySet());
        all.addAll(TEST_ROOMS.keySet());
        return new java.util.ArrayList<>(all);
    }

    public static synchronized int roomCount() {
        return ROOMS.size();
    }

    public static synchronized int completeCount() {
        int n = 0;
        for (Room r : ROOMS.values()) {
            if (r.complete()) {
                n++;
            }
        }
        return n;
    }

    /** Every room touched so far that is not finished, worst first - what the recorder still needs. */
    public static synchronized List<String> incomplete() {
        List<Room> rooms = new ArrayList<>(ROOMS.values());
        rooms.removeIf(Room::complete);
        rooms.sort((a, b) -> Double.compare(a.completeness(), b.completeness()));
        List<String> out = new ArrayList<>();
        for (Room r : rooms) {
            out.add(String.format(Locale.US, "%s  %.0f%%", r.name, r.completeness() * 100.0));
        }
        return out;
    }

    /**
     * Reads whatever of {@code room} is loaded right now into the library.
     *
     * <p>Safe to call repeatedly on the same room: each call only fills in columns that have not been read, so
     * walking through a room twice from different directions completes it rather than overwriting it. Only
     * genuinely loaded blocks are taken - an unloaded chunk reads as air, and writing that in would record a
     * room full of holes and then call it finished.
     *
     * @return how many new columns this call added
     */
    public static synchronized int capture(Level level, DungeonLayout layout, int room) {
        load();
        String name = layout.name(room);
        if (name == null || name.isBlank()) {
            return 0;
        }
        int[] tiles = layout.tiles(room);
        if (tiles == null || tiles.length == 0) {
            return 0;
        }
        int minGx = Integer.MAX_VALUE;
        int minGz = Integer.MAX_VALUE;
        int maxGx = Integer.MIN_VALUE;
        int maxGz = Integer.MIN_VALUE;
        for (int idx : tiles) {
            int gx = idx % DungeonLayout.GRID;
            int gz = idx / DungeonLayout.GRID;
            minGx = Math.min(minGx, gx);
            minGz = Math.min(minGz, gz);
            maxGx = Math.max(maxGx, gx);
            maxGz = Math.max(maxGz, gz);
        }
        int sizeX = (maxGx - minGx + 1) * TILE;
        int sizeZ = (maxGz - minGz + 1) * TILE;
        Room r = ROOMS.get(name);
        if (r == null || r.sizeX != sizeX || r.sizeZ != sizeZ) {
            r = new Room(name, sizeX, sizeZ);
            ROOMS.put(name, r);
        }
        BlockPos origin = DungeonLayout.cellCenter(minGz * DungeonLayout.GRID + minGx);
        int worldX0 = origin.getX() - TILE / 2;
        int worldZ0 = origin.getZ() - TILE / 2;

        int added = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = 0; x < sizeX; x++) {
            for (int z = 0; z < sizeZ; z++) {
                int col = z * sizeX + x;
                if (r.seenColumn[col]) {
                    continue;
                }
                cursor.set(worldX0 + x, MIN_Y, worldZ0 + z);
                if (!com.killer560.hub.chunkcache.ChunkCacheManager.isLoadedOrCached(level, cursor)) {
                    continue; // not loaded: recording air here would be a lie that never gets corrected
                }
                for (int y = MIN_Y; y <= MAX_Y; y++) {
                    cursor.set(worldX0 + x, y, worldZ0 + z);
                    BlockState state = level.getBlockState(cursor);
                    // The FULL state, not just the block id. Storing only the id threw away every stair's
                    // facing, every door's hinge and every lever's wall before the data was even saved - and a
                    // room rebuilt from that has its geometry right and everything directional pointing the
                    // same wrong way, which is worse than obviously broken because it looks nearly correct.
                    // serialize() produces "minecraft:stone_brick_stairs[facing=north,...]", which the existing
                    // string palette already holds; a plain id from an older capture still parses as the
                    // default state, so nothing recorded before this breaks.
                    r.blocks[r.index(x, y, z)] = r.paletteFor(
                            net.minecraft.commands.arguments.blocks.BlockStateParser.serialize(state));
                }
                r.seenColumn[col] = true;
                added++;
            }
        }
        return added;
    }

    /**
     * Records a starred mob spawn, in room-local coordinates.
     *
     * <p>Rounded to whole blocks on purpose. Hypixel spawns a mob at a spot, not at a float, and keeping the
     * fractional position would make two sightings of the same spawn look like two different ones and defeat
     * the deduplication entirely.
     */
    public static synchronized void recordMobSpawn(String roomName, int localX, int localY, int localZ,
                                                   String kind) {
        Room r = ROOMS.get(roomName);
        if (r == null) {
            return;
        }
        r.mobSpawns.add(localX + "," + localY + "," + localZ + "," + kind);
    }

    public static synchronized void saveAll() {
        try {
            Files.createDirectories(DIR);
            for (Room r : ROOMS.values()) {
                Path f = DIR.resolve(safeName(r.name) + ".json");
                Files.writeString(f, GSON.toJson(toJson(r)), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            LOGGER.error("Could not save the room library", e);
        }
    }

    private static String safeName(String name) {
        return name.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static JsonObject toJson(Room r) {
        JsonObject o = new JsonObject();
        o.addProperty("name", r.name);
        o.addProperty("sizeX", r.sizeX);
        o.addProperty("sizeZ", r.sizeZ);
        o.addProperty("minY", MIN_Y);
        o.addProperty("maxY", MAX_Y);
        JsonArray pal = new JsonArray();
        for (String p : r.palette) {
            pal.add(p);
        }
        o.add("palette", pal);
        JsonArray blocks = new JsonArray();
        for (short b : r.blocks) {
            blocks.add(b);
        }
        o.add("blocks", blocks);
        JsonArray seen = new JsonArray();
        for (boolean b : r.seenColumn) {
            seen.add(b);
        }
        o.add("seenColumn", seen);
        JsonArray spawns = new JsonArray();
        for (String m : r.mobSpawns) {
            spawns.add(m);
        }
        o.add("mobSpawns", spawns);
        return o;
    }

    private static Room fromJson(JsonObject o) {
        String name = o.get("name").getAsString();
        Room r = new Room(name, o.get("sizeX").getAsInt(), o.get("sizeZ").getAsInt());
        JsonArray pal = o.getAsJsonArray("palette");
        for (int i = 0; i < pal.size(); i++) {
            r.paletteFor(pal.get(i).getAsString());
        }
        JsonArray blocks = o.getAsJsonArray("blocks");
        for (int i = 0; i < blocks.size() && i < r.blocks.length; i++) {
            r.blocks[i] = blocks.get(i).getAsShort();
        }
        JsonArray seen = o.getAsJsonArray("seenColumn");
        for (int i = 0; i < seen.size() && i < r.seenColumn.length; i++) {
            r.seenColumn[i] = seen.get(i).getAsBoolean();
        }
        if (o.has("mobSpawns")) {
            JsonArray spawns = o.getAsJsonArray("mobSpawns");
            for (int i = 0; i < spawns.size(); i++) {
                r.mobSpawns.add(spawns.get(i).getAsString());
            }
        }
        return r;
    }
}
