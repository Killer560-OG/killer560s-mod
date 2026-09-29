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
     * Extra columns captured on each side, to take in the walls.
     *
     * <p>killer560 (2026-09-28): "it isnt getting the walls around the room". Correct, and here is why: the
     * dungeon grid's cells are 32 blocks apart but {@link #TILE} is 31, so a window centred on a cell covers
     * centre-15 to centre+15 and the boundary column at centre+/-16 is never read. That column is exactly where
     * the wall between two rooms stands. Checked against his own capture before changing anything: in
     * Entrance.json the x=0 edge holds chiseled stone brick while x=30 holds plain stone, so the window was
     * landing inside the wall on one side and out in the rock on the other.
     *
     * <p>One column each side makes it 33 across a 32 pitch, so neighbouring rooms overlap by one - which is
     * right, because they SHARE that wall rather than each owning half of it.
     *
     * <p>This changes the shape of a captured room, so rooms captured before it are a different size and are
     * replaced rather than merged - {@code capture} already rebuilds a Room whose dimensions do not match.
     * Those older captures keep their missing walls until they are scanned again.
     */
    public static final int WALL_MARGIN = 1;

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

    /**
     * Loads the library, off the render thread.
     *
     * <p>killer560 (2026-09-28): "still froze on boot [...] on joining the sim sorry." His library had reached
     * 187 MB, and all of it was read and parsed on the thread that draws the game, so joining the sim stopped
     * the client dead for as long as that took.
     *
     * <p>Two things were wrong and both are fixed. The format was JSON numbers - 77,841 of them per room,
     * every one tokenised - and is now gzipped bytes, roughly a twentieth of the size and needing no parsing.
     * And the work happened on the render thread at all, which no amount of making it faster would have made
     * safe: a library big enough is always going to outlast a frame. It runs on its own thread now and the
     * callers wait on {@link #isReady()} instead of on the disk.
     */
    public static void loadAsync() {
        synchronized (RoomLibrary.class) {
            if (loaded || loading) {
                return;
            }
            loading = true;
        }
        Thread t = new Thread(RoomLibrary::load, "killer560smod-roomlibrary");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Forgets the library and reads it again.
     *
     * <p>For the gametest, which copies a real library into place AFTER the client has already started and
     * loaded an empty one. Without this the test measures loading nothing, which is exactly the kind of green
     * that proves the opposite of what it claims.
     */
    public static void forceReload() {
        synchronized (RoomLibrary.class) {
            loaded = false;
            loading = false;
            readSoFar = 0;
        }
        loadAsync();
    }

    /** Whether the library has finished loading. */
    public static synchronized boolean isReady() {
        return loaded;
    }

    /** Rooms read so far, for a progress line while it loads. */
    /** Reads a volatile counter rather than the map, so a progress line never waits on the loader. */
    public static int loadedSoFar() {
        return readSoFar;
    }

    private static volatile boolean loading;

    /**
     * Reads the library from disk.
     *
     * <p>Deliberately NOT {@code synchronized}, and that is the whole point of this version. It used to be,
     * which meant the loading thread held the class monitor for the entire read - and every other method here
     * is synchronized too, so the render thread calling {@link #completeCount()} to draw the menu blocked
     * behind it for the full four seconds. Moving the work off the render thread achieved nothing while the
     * render thread still had to wait for the lock to draw a single frame. That is the freeze killer560 kept
     * seeing "on creation".
     *
     * <p>So the files are read into a local map with no lock held at all, and the lock is taken once at the
     * end to swap it in. The render thread now waits for a map assignment rather than for a disk read.
     */
    public static void load() {
        synchronized (RoomLibrary.class) {
            if (loaded) {
                return;
            }
        }
        long startedAt = System.currentTimeMillis();
        Map<String, Room> fresh = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        int upgraded = 0;
        try {
            if (Files.isDirectory(DIR)) {
                List<Path> jsons;
                try (var files = Files.list(DIR)) {
                    jsons = files.filter(p -> p.toString().endsWith(".json")).toList();
                }
                for (Path f : jsons) {
                    try {
                        JsonObject json = JsonParser.parseString(
                                Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
                        Room r = fromJson(json);
                        if (r != null) {
                            fresh.put(r.name, r);
                            readSoFar = fresh.size();
                            if (!json.has("blocksZ")) {
                                // Written in the old number-array format. Rewritten now, while it is already
                                // in memory, so this load is the last slow one rather than every load being
                                // slow until the recorder happens to save that room again.
                                upgraded++;
                                Files.writeString(f, GSON.toJson(toJson(r)), StandardCharsets.UTF_8);
                            }
                        }
                    } catch (Exception e) {
                        // The exception CLASS, not the exception. Gson puts the text it failed to parse into
                        // the message, so logging the throwable wrote the whole file to disk - killer560's log
                        // reached 50 MB from four corrupt rooms. The class and the file name say everything
                        // useful.
                        LOGGER.warn("Could not read captured room {} ({}) - moving it aside",
                                f.getFileName(), e.getClass().getSimpleName());
                        quarantine(f);
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.error("Could not load the room library", e);
        }
        int complete;
        synchronized (RoomLibrary.class) {
            ROOMS.clear();
            ROOMS.putAll(fresh);
            // Set on EVERY path, including "there is no rooms folder yet". The old code returned early in that
            // case without setting it, so a first run with nothing captured left the library permanently "not
            // ready" and every later load refused to start - which is how the gametest found this.
            loaded = true;
            loading = false;
            complete = completeCountLocked();
        }
        LOGGER.info("Room library: {} room(s) on disk, {} complete, {} upgraded to the compact format, {} ms",
                fresh.size(), complete, upgraded, System.currentTimeMillis() - startedAt);
    }

    /** Rooms read so far by an in-progress load, for a progress line. Written by the loader thread only. */
    private static volatile int readSoFar;

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

    /**
     * Moves a room file that cannot be read out of the way.
     *
     * <p>killer560's four corrupt files were entirely NUL bytes, which is what a file looks like when its data
     * never reached the disk - his machine bugchecked while they were being written. They are unrecoverable,
     * and leaving them in place means paying to fail on them on every single load.
     *
     * <p>Renamed rather than deleted. They look worthless, but they are his data and "they look worthless" is
     * not a good enough reason to remove something permanently. The recorder captures those rooms again on its
     * next pass anyway.
     */
    private static void quarantine(Path f) {
        try {
            Files.move(f, f.resolveSibling(f.getFileName() + ".corrupt"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            LOGGER.warn("Could not move aside the unreadable room {}", f.getFileName());
        }
    }

    /** {@link #completeCount()} without taking the lock, for callers that already hold it. */
    private static int completeCountLocked() {
        int n = 0;
        for (Room r : ROOMS.values()) {
            if (r.complete()) {
                n++;
            }
        }
        return n;
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
    /** {@link DungeonLayout}'s placeholder for a room it cannot name yet - never a real room. */
    private static final String UNIDENTIFIED = "Unknown";

    public static synchronized int capture(Level level, DungeonLayout layout, int room) {
        return capture(level, layout, room, Integer.MAX_VALUE);
    }

    /**
     * Captures a room, stopping after {@code columnBudget} new columns.
     *
     * <p>The budget exists because the recorder now sweeps every room in render distance rather than only the
     * one you stand in, and this runs every tick. A room is 961 columns of 81 blocks each; a whole map's worth
     * in one tick is close to two million block reads and would stutter badly. Spreading it costs no coverage:
     * columns already captured are skipped, and the 25 second scan window is hundreds of ticks long.
     */
    public static synchronized int capture(Level level, DungeonLayout layout, int room, int columnBudget) {
        load();
        if (columnBudget <= 0) {
            return 0;
        }
        String name = layout.name(room);
        if (name == null || name.isBlank() || UNIDENTIFIED.equals(name)) {
            // "Unknown" is DungeonLayout's placeholder for a room it has not identified yet, not a room name.
            // Capturing it wrote the geometry a second time under that placeholder - killer560's first live
            // scan produced Entrance.json and Unknown.json that were identical in all 77841 block positions,
            // and the recorder reported "2 of 2 rooms complete" for what was one room seen twice.
            //
            // The duplicate is the harmless half. Every unidentified room shares the one placeholder, so a
            // later one would overwrite it, and a file holding half of one room and half of another would look
            // exactly like a captured room to the sim. Waiting costs nothing: the name resolves a moment later
            // and the same room is captured properly on the next scan tick.
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
        int sizeX = (maxGx - minGx + 1) * TILE + WALL_MARGIN * 2;
        int sizeZ = (maxGz - minGz + 1) * TILE + WALL_MARGIN * 2;
        Room r = ROOMS.get(name);
        if (r == null || r.sizeX != sizeX || r.sizeZ != sizeZ) {
            r = new Room(name, sizeX, sizeZ);
            ROOMS.put(name, r);
        }
        BlockPos origin = DungeonLayout.cellCenter(minGz * DungeonLayout.GRID + minGx);
        int worldX0 = origin.getX() - TILE / 2 - WALL_MARGIN;
        int worldZ0 = origin.getZ() - TILE / 2 - WALL_MARGIN;

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
                if (added >= columnBudget) {
                    // Out of budget. The rest of this room is picked up on a later tick - seenColumn means
                    // resuming costs nothing and never re-reads what is already stored.
                    return added;
                }
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

    /** Shorts to gzipped base64. Big-endian, so the encoding does not depend on the machine that wrote it. */
    private static String encodeShorts(short[] values) {
        try {
            var raw = new java.io.ByteArrayOutputStream();
            try (var gz = new java.util.zip.GZIPOutputStream(raw);
                 var out = new java.io.DataOutputStream(gz)) {
                for (short v : values) {
                    out.writeShort(v);
                }
            }
            return java.util.Base64.getEncoder().encodeToString(raw.toByteArray());
        } catch (Exception e) {
            LOGGER.warn("Could not compress a room's blocks", e);
            return "";
        }
    }

    private static void decodeShorts(String encoded, short[] into) {
        if (encoded == null || encoded.isEmpty()) {
            return;
        }
        try (var in = new java.io.DataInputStream(new java.util.zip.GZIPInputStream(
                new java.io.ByteArrayInputStream(java.util.Base64.getDecoder().decode(encoded))))) {
            for (int i = 0; i < into.length; i++) {
                into[i] = in.readShort();
            }
        } catch (java.io.EOFException expected) {
            // A shorter array than this room expects: everything past it stays at its default. Better a
            // partly-read room than a discarded one.
        } catch (Exception e) {
            LOGGER.warn("Could not read a room's compressed blocks", e);
        }
    }

    /** One bit per column rather than one byte, since it is a boolean array of a thousand or so. */
    private static String encodeBits(boolean[] values) {
        byte[] packed = new byte[(values.length + 7) / 8];
        for (int i = 0; i < values.length; i++) {
            if (values[i]) {
                packed[i >> 3] |= (byte) (1 << (i & 7));
            }
        }
        return java.util.Base64.getEncoder().encodeToString(packed);
    }

    private static void decodeBits(String encoded, boolean[] into) {
        if (encoded == null || encoded.isEmpty()) {
            return;
        }
        try {
            byte[] packed = java.util.Base64.getDecoder().decode(encoded);
            for (int i = 0; i < into.length && (i >> 3) < packed.length; i++) {
                into[i] = (packed[i >> 3] & (1 << (i & 7))) != 0;
            }
        } catch (Exception e) {
            LOGGER.warn("Could not read a room's seen-column data", e);
        }
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
        // Compressed, not a list of numbers. killer560 (2026-09-28): "still froze on boot" - his library had
        // reached 187 MB and every byte of it was parsed on the render thread before the title screen. A room
        // is 77,841 block ids; written as JSON numbers that is half a megabyte of text per 1x1 room and five
        // for a 2x2, and Gson has to tokenise every single one.
        //
        // The same data as gzipped bytes is roughly a twentieth of the size and needs no parsing at all. That
        // is the difference between a library that is slow and one that does not fit in a boot.
        o.addProperty("blocksZ", encodeShorts(r.blocks));
        o.addProperty("seenZ", encodeBits(r.seenColumn));
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
        // Both formats are read. Everything already captured is in the old one, and rewriting 187 MB of it
        // on load would be a worse first boot than the one being fixed - each room upgrades itself the next
        // time it is saved, which the recorder does every run.
        if (o.has("blocksZ")) {
            decodeShorts(o.get("blocksZ").getAsString(), r.blocks);
        } else if (o.has("blocks")) {
            JsonArray blocks = o.getAsJsonArray("blocks");
            for (int i = 0; i < blocks.size() && i < r.blocks.length; i++) {
                r.blocks[i] = blocks.get(i).getAsShort();
            }
        }
        if (o.has("seenZ")) {
            decodeBits(o.get("seenZ").getAsString(), r.seenColumn);
        } else if (o.has("seenColumn")) {
            JsonArray seen = o.getAsJsonArray("seenColumn");
            for (int i = 0; i < seen.size() && i < r.seenColumn.length; i++) {
                r.seenColumn[i] = seen.get(i).getAsBoolean();
            }
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
