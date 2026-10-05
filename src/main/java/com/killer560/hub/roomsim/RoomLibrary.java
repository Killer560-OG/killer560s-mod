package com.killer560.hub.roomsim;

import com.killer560.hub.util.ModPaths;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import com.killer560.hub.util.ModLog;

/**
 * The captured rooms: their blocks, and how much of each one has actually been seen.
 *
 * <p>This exists because no public dungeon dataset ships room GEOMETRY. The well-known references store secret
 * coordinates and a way to tell which room you are standing in, which is all a waypoint mod needs and is not
 * enough to build a room you can walk through (checked 2026-09-28). So the blocks have to come from real runs,
 * and this is where they land. Once a room is complete it never needs capturing again.
 *
 * <p><b>The finished library ships inside the jar</b> ({@link #BUNDLED_ROOT}), so the sim works the same in
 * every instance instead of being only as good as what that instance happened to walk through. His own captures
 * still win over the shipped copies - see {@link #mergeBundled} for the exact rule.
 *
 * <p><b>Completeness is per column, not per room.</b> A room is only ever partly loaded - you see the half you
 * walked through and the rest is outside render distance or behind a wall - so "captured" cannot be a flag set
 * the first time a room is entered. Each room records which of its 31x31-per-tile columns have been read, and is
 * only finished when all of them have.
 *
 * <p>Read-only since 2026-10-04: the Room Recorder that wrote these captures was removed and lives at git tag
 * {@code room-recorder-last} (see docs/SIM.md). Nothing here writes a room any more.
 */
public final class RoomLibrary {

    private static final Logger LOGGER = ModLog.get("killer560smod-roomsim");
    private static final Path DIR =
            ModPaths.config("killer560smod-rooms");

    private static final String MOD_ID = "killer560smod";

    /**
     * The baseline library shipped inside the jar.
     *
     * <p>Rooms used to exist only per-instance in {@link #DIR}, which meant the sim was only as good as
     * whatever that particular Prism instance happened to have walked through. Measured on 2026-09-29: his
     * "Map Logger" instance held 110 files of which 60 were usable and every single usable one was 1x1 - the
     * other 43 were stored at the OLD footprint, so {@link Room#currentFormat()} threw them out, and every
     * multi-tile room he had ever captured was among them. Generated floors there were entirely 1x1 and looked
     * sparse. His "26.1.2 (Mod Only Test)" instance had all 135 rooms complete and correct at the same moment.
     * Same mod, same code, wildly different sim, and nothing on screen said why.
     *
     * <p>So the 135 good rooms ship with the mod: 4.6 MB against a 33 MB jar. Every instance now starts from
     * the same baseline and his own captures still win over it - see the merge in {@link #mergeBundled}.
     */
    private static final String BUNDLED_ROOT = "assets/killer560smod/rooms";

    /**
     * Names of the bundled rooms, one per line, no {@code .json}.
     *
     * <p>Driven off a list rather than by listing the directory: a mod's resources live inside a jar (or, in a
     * dev run, a loose folder, or a Loom-remapped temporary), and walking a directory through a zip
     * {@code FileSystem} is not something to depend on across all three. A text file reads the same way
     * everywhere.
     */
    private static final String BUNDLED_INDEX = BUNDLED_ROOT + "/index.txt";

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
     * not in the current format (see {@link Room#currentFormat()}).
     */
    public static final int WALL_MARGIN = 1;

    /**
     * Vertical slice kept per room.
     *
     * <p>Dungeon floors sit at y 69 and the tallest rooms do not reach y 140. Storing the whole world column
     * would multiply the library for nothing, and cutting it too close silently loses ceilings, which is the
     * kind of loss you only notice once the room is rebuilt and has a hole in it.
     */
    /**
     * The y band a NEW capture records.
     *
     * <p>Was 60..140, which cut the bottom off 58 of his 135 rooms - anything with a basement, a ravine or a
     * pit. killer560 (2026-09-29): "Make the scanner read the full height that way it cannot accidentally
     * miss something." So the scan now covers the whole world column rather than a guessed band, and no
     * dungeon geometry can fall outside it by construction.
     *
     * <p>That would be a lot of memory to keep - the full column is three times the old band, and a library
     * that got too big to load is a mistake this file has already made once. It is not kept: a room is
     * SAVED trimmed to the y range that actually holds something, so files and loaded rooms stay the size of
     * the room rather than the size of the world.
     *
     * <p>A room already on disk keeps its own band - see {@link Room#minY} - so changing this invalidates
     * nothing.
     */
    public static final int MIN_Y = -64;
    public static final int MAX_Y = 320;

    /** name -> room, case-insensitive. */
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
         * Blocks of wall captured outside the room on each side.
         *
         * <p>Stored rather than worked out from {@link #sizeX}. {@code RoomPlacer} used to infer it with
         * {@code size % TILE == WALL_MARGIN * 2}, which only ever worked because the footprint maths below was
         * wrong in a way that happened to leave every size a multiple of 31 plus 2. With the footprint correct
         * a three-tile room is 97 wide and 97 % 31 is 4, so the inference would have silently decided those
         * rooms had no margin and pasted them one block off. A number this load-bearing belongs in the file.
         */
        public int margin = WALL_MARGIN;

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

        /**
         * Came out of the jar and has not been touched since.
         *
         * <p>Counted in the load log line. Cleared by a synthetic {@code set} or {@code markComplete}.
         */
        boolean fromJar;

        /** Whether this room is the untouched copy that shipped in the jar. */
        public boolean isBundled() {
            return fromJar;
        }

        /**
         * The y band THIS capture covers.
         *
         * <p>Per room, not global, and that is the point. {@link RoomLibrary#MIN_Y} was 60 and every capture
         * was indexed against that constant, so a room whose structure runs below 60 was sliced off at the
         * bottom and the missing blocks were simply never recorded. killer560 saw it as "purple flags was
         * missing the bottom part of it"; measured across the library, 58 of his 135 rooms have built
         * structure sitting on that floor.
         *
         * <p>The file has always written its own {@code minY}/{@code maxY} - the loader just ignored them and
         * used the constant. Reading them back means the floor can be lowered for NEW captures while every
         * existing file keeps loading at the band it was written with, so nothing has to be re-recorded before
         * the change is safe to ship.
         */
        public final int minY;
        public final int maxY;

        Room(String name, int sizeX, int sizeZ) {
            this(name, sizeX, sizeZ, MIN_Y, MAX_Y);
        }

        Room(String name, int sizeX, int sizeZ, int minY, int maxY) {
            this.name = name;
            this.sizeX = sizeX;
            this.sizeZ = sizeZ;
            this.minY = minY;
            this.maxY = maxY;
            this.blocks = new short[sizeX * sizeZ * (maxY - minY + 1)];
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
            return (y - minY) * sizeX * sizeZ + z * sizeX + x;
        }

        /**
         * The captured palette index at these room-local coordinates, or -1 outside the capture.
         *
         * <p>Reads that go through this cannot crash, and that is the whole point. Every read site used to
         * walk {@code MIN_Y..MAX_Y} because those constants WERE the band every room was indexed against;
         * once each room carried its own band, a caller still using the constants indexed a room whose band
         * starts at 60 with y -64 and got element -135,036 of an 88,209-long array. That crashed the sim
         * server outright on the first floor built (2026-09-29). Widening the constants without giving the
         * reads a bound is what made it possible, so the bound lives here where no caller can forget it.
         *
         * <p>-1 for "outside" is not a special case: it is the same value a column that was never captured
         * already carries, and every caller already had to handle that.
         */
        public short at(int x, int y, int z) {
            if (x < 0 || z < 0 || x >= sizeX || z >= sizeZ || y < minY || y > maxY) {
                return -1;
            }
            return blocks[index(x, y, z)];
        }

        /**
         * Writes one block, for rooms built in code rather than captured.
         *
         * <p>Also marks the column read, because a synthetic room is complete by construction - there is no
         * "rest of it" still out of render distance.
         */
        void set(int x, int y, int z, String blockId) {
            if (x < 0 || z < 0 || x >= sizeX || z >= sizeZ || y < minY || y > maxY) {
                return;
            }
            blocks[index(x, y, z)] = paletteFor(blockId);
            seenColumn[z * sizeX + x] = true;
            fromJar = false;
        }

        /** Marks every column read. For synthetic rooms; a captured one earns this a column at a time. */
        void markComplete() {
            fromJar = false;
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

        /**
         * Captured at the footprint this version of the mod uses.
         *
         * <p>A room stored at an older footprint is not a room this mod can use: {@code RoomPlacer} would paste
         * it at the wrong size.
         * So it must not be counted as done anywhere, or the progress numbers promise work that still has to be
         * redone - which is exactly what happened on 2026-09-29, when 49 of 102 "complete" rooms turned out to
         * be at the old inflated size.
         *
         * <p>Every valid footprint is {@code tiles * 32 + 1}: {@code tiles * TILE} plus the one-block seam
         * between tiles plus {@link RoomLibrary#WALL_MARGIN} on each side.
         */
        public boolean currentFormat() {
            return margin == WALL_MARGIN
                    && sizeX >= TILE + WALL_MARGIN * 2 && (sizeX - 1) % (TILE + 1) == 0
                    && sizeZ >= TILE + WALL_MARGIN * 2 && (sizeZ - 1) % (TILE + 1) == 0;
        }

        /** Lowest and highest captured y that holds anything but air, worked out once. */
        private int contentMinY = Integer.MIN_VALUE;
        private int contentMaxY = Integer.MIN_VALUE;

        /**
         * The lowest y in this room that is not air.
         *
         * <p>For {@link SimAltitude}, which shifts a whole floor so its lowest block sits just above the void.
         * Measured rather than assumed: the capture window runs from {@link RoomLibrary#MIN_Y} to
         * {@link RoomLibrary#MAX_Y} and most rooms use only part of it, so the window's edges say nothing
         * about where the room's blocks actually are.
         *
         * <p>Computed once per room and kept. A room is about 80,000 block ids, and this walks them once.
         */
        public int contentMinY() {
            measureContent();
            return contentMinY;
        }

        /** The highest y in this room that is not air. */
        public int contentMaxY() {
            measureContent();
            return contentMaxY;
        }

        private synchronized void measureContent() {
            if (contentMinY != Integer.MIN_VALUE) {
                return;
            }
            // THIS room's band, not the global constants. index() is relative to minY, so walking the
            // constants over a room loaded at an older, narrower band indexes straight out of the array -
            // and SimAltitude calls this on every build.
            // EVERY kind of nothing, not just "minecraft:air".
            //
            // Above the build area the world is minecraft:void_air and in caves it is minecraft:cave_air, and
            // treating those as content made a freshly scanned room measure as full to y 320: Pipes stored 293
            // layers where about 70 hold anything. That is three times the file for nothing - and worse,
            // SimAltitude positions a whole floor from this range, so it would have placed floors against a
            // ceiling that is not there.
            java.util.Set<Short> empty = new java.util.HashSet<>();
            for (int i = 0; i < palette.size(); i++) {
                String state = palette.get(i);
                if ("minecraft:air".equals(state) || "minecraft:void_air".equals(state)
                        || "minecraft:cave_air".equals(state)) {
                    empty.add((short) i);
                }
            }
            int lo = maxY;
            int hi = minY;
            boolean any = false;
            for (int y = minY; y <= maxY; y++) {
                boolean solid = false;
                for (int z = 0; z < sizeZ && !solid; z++) {
                    for (int x = 0; x < sizeX; x++) {
                        short id = blocks[index(x, y, z)];
                        if (id >= 0 && !empty.contains(id)) {
                            solid = true;
                            break;
                        }
                    }
                }
                if (solid) {
                    any = true;
                    lo = Math.min(lo, y);
                    hi = Math.max(hi, y);
                }
            }
            contentMinY = any ? lo : MIN_Y;
            contentMaxY = any ? hi : MAX_Y;
        }

        /**
         * Why this capture holds blocks that are not this room's, or null when it is sound.
         *
         * <p>Set by {@link RoomTileAudit} at load, not stored in the file: it is derived from the blocks, so
         * no stale list has to be maintained.
         */
        public String corruptReason;

        public boolean usable() {
            return currentFormat() && complete() && corruptReason == null;
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
        // The measured doorways belong to the rooms that are about to be replaced, so they go too - a mask
        // that outlived its room would lay the next floor out against geometry that is no longer there.
        RoomDoors.clearCache();
        // Same reasoning for the capture rotations: they are derived from the blocks of the rooms being
        // replaced, and a stale one puts a re-captured room's secrets back in the corner it was just fixed
        // out of. Keyed by name, so it would survive the reload unnoticed.
        RoomCaptureRotation.clearCache();
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
     * is synchronized too, so the render thread asking for a room to draw the menu blocked
     * behind it for the full four seconds. Moving the work off the render thread achieved nothing while the
     * render thread still had to wait for the lock to draw a single frame. That is the freeze killer560 kept
     * seeing "on creation".
     *
     * <p>So the files are read into a local map with no lock held at all, and the lock is taken once at the
     * end to swap it in. The render thread now waits for a map assignment rather than for a disk read.
     *
     * <p>The BUNDLED library is read the same way, into the same local map, before the lock is taken - see
     * {@link #mergeBundled}. Reading 4.6 MB out of the jar is the same kind of work as reading it off disk and
     * gets the same treatment.
     */
    public static void load() {
        synchronized (RoomLibrary.class) {
            if (loaded) {
                return;
            }
        }
        long startedAt = System.currentTimeMillis();
        Map<String, Room> fresh = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
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
        int onDisk = fresh.size();
        // Still no lock held. mergeBundled reads the jar and merges into the same local map.
        int rescued = mergeBundled(fresh);
        // AFTER the merge, so a bundled room that replaced a bad local one is what gets checked - and before
        // the map is swapped in, so nothing can ever see an unaudited library. Still off the render thread,
        // for the same reason the reads above are.
        RoomTileAudit.run(fresh);
        int fromJar = 0;
        for (Room r : fresh.values()) {
            if (r.fromJar) {
                fromJar++;
            }
        }
        int complete;
        synchronized (RoomLibrary.class) {
            ROOMS.clear();
            ROOMS.putAll(fresh);
            // Set on EVERY path, including "there is no rooms folder yet", no mod container, and no bundled
            // index. The old code returned early in that case without setting it, so a first run with nothing
            // captured left the library permanently "not ready" and every later load refused to start - which
            // is how the gametest found this. mergeBundled never throws for the same reason.
            loaded = true;
            loading = false;
            complete = completeCountLocked();
        }
        LOGGER.info("Room library: {} room(s), {} from the jar and {} from disk ({} read off disk), "
                        + "{} bundled room(s) replaced an unusable local copy, {} complete, {} ms",
                fresh.size(), fromJar, fresh.size() - fromJar, onDisk, rescued, complete,
                System.currentTimeMillis() - startedAt);
    }

    /**
     * Overlays the shipped library onto what was read off disk, and returns how many unusable local rooms the
     * jar rescued.
     *
     * <p>The merge rule, which is the whole point of shipping the rooms at all:
     * <ul>
     *   <li>A USABLE disk room wins. His own newer capture always beats the shipped copy - that is what makes
     *       re-capturing a room still worth doing.</li>
     *   <li>An UNUSABLE disk room loses to a usable bundled one. That is the Map Logger case exactly: 43 rooms
     *       stored at the old footprint, filtered out by {@link Room#currentFormat()}, sitting in the map under
     *       the right names and hiding the good shipped copies behind them.</li>
     *   <li>If NEITHER is usable the disk one stays.</li>
     *   <li>A room that exists on only one side is kept as it is.</li>
     * </ul>
     *
     * <p>The usable disk room is decided BEFORE the bundled file's blocks are decoded, so the good instance -
     * where all 135 disk rooms already win - does not pay to gunzip 20 MB of block arrays it is about to throw
     * away. Only the small header fields are needed to know the disk copy wins.
     *
     * <p>Never throws. A missing mod container or a missing index logs one line and leaves the disk rooms
     * exactly as they were; the sim then behaves the way it did before this existed rather than not loading.
     */
    private static int mergeBundled(Map<String, Room> fresh) {
        List<String> names;
        try {
            java.util.Optional<Path> index = bundled(BUNDLED_INDEX);
            if (index.isEmpty()) {
                LOGGER.warn("No bundled room library in this jar ({} is missing) - using only the {} room(s) "
                        + "in this instance's config folder", BUNDLED_INDEX, fresh.size());
                return 0;
            }
            names = new ArrayList<>();
            for (String line : Files.readString(index.get(), StandardCharsets.UTF_8).split("\\R")) {
                // The index holds safeName() output, so any character outside that alphabet cannot be part
                // of a room's file name - which disposes of the UTF-8 BOM on the first line, of a stray CR,
                // and of trailing spaces in one go. Left in, the BOM becomes part of the first room's file
                // name and loses exactly one room, silently.
                String name = line.replaceAll("[^A-Za-z0-9._-]", "");
                if (!name.isEmpty()) {
                    names.add(name);
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Could not read the bundled room index ({}) - using only this instance's rooms",
                    e.getClass().getSimpleName());
            return 0;
        }

        int rescued = 0;
        for (String fileName : names) {
            try {
                java.util.Optional<Path> p = bundled(BUNDLED_ROOT + "/" + fileName + ".json");
                if (p.isEmpty()) {
                    LOGGER.warn("Bundled room {} is listed in the index but not in the jar", fileName);
                    continue;
                }
                JsonObject json = JsonParser.parseString(
                        Files.readString(p.get(), StandardCharsets.UTF_8)).getAsJsonObject();
                // The room's NAME is the one in the file, not the file name - the files are written through
                // safeName(), so "Arrow Trap" is stored as Arrow_Trap.json and the map is keyed by the former.
                String name = json.has("name") ? json.get("name").getAsString() : null;
                if (name == null || name.isBlank()) {
                    continue;
                }
                Room disk = fresh.get(name);
                if (disk != null && disk.usable()) {
                    // His own capture wins; do not even decode the shipped blocks.
                    continue;
                }
                Room b = fromJson(json);
                if (b == null) {
                    continue;
                }
                b.fromJar = true;
                if (disk == null) {
                    fresh.put(b.name, b);
                    readSoFar = fresh.size();
                } else if (b.usable()) {
                    // The measured case: an old-footprint or half-captured local room was hiding a good one.
                    fresh.put(b.name, b);
                    rescued++;
                }
                // else: neither is usable, so the disk room stays and keeps its partial progress.
            } catch (Exception e) {
                LOGGER.warn("Could not read the bundled room {} ({})", fileName, e.getClass().getSimpleName());
            }
        }
        return rescued;
    }

    /** One file inside this mod's own jar, or empty when there is no container (never throws). */
    private static java.util.Optional<Path> bundled(String inner) {
        try {
            return FabricLoader.getInstance().getModContainer(MOD_ID).flatMap(c -> c.findPath(inner));
        } catch (Throwable t) {
            return java.util.Optional.empty();
        }
    }

    /** Rooms read so far by an in-progress load, for a progress line. Written by the loader thread only. */
    private static volatile int readSoFar;

    /**
     * Rooms built in code, kept apart from captured ones.
     *
     * <p>A SEPARATE map on purpose (killer560, 2026-09-28: "make sure it's all in a spot that can easily be
     * deleted and won't accidentally contaminate other parts of the mod"). If a synthetic room shared the real
     * map it would be counted as a captured room - it would end up in the shipped library looking exactly like a real one. Nothing in
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

    /**
     * How many grid cells a captured room covers, as {@code tilesX * tilesZ}.
     *
     * <p>The inverse of the capture footprint ({@code tiles * 32 + 1}), in one place, because the layout planner and the paste MUST agree
     * about it. They did not between 00:27 and 01:20 on 2026-09-29 and the result was every multi-tile room
     * pasted over its neighbour.
     *
     * @return 0 when the room is unknown or not in the current format
     */
    /**
     * Whether this room is one the sim will actually place.
     *
     * <p>The same question {@link Room#usable()} answers, by name, so anything choosing rooms from the
     * outside asks the one authority rather than re-deriving it - a screen, a layout and a test each with
     * their own idea of usable is three places to drift.
     */
    public static synchronized boolean isUsable(String name) {
        Room r = get(name);
        return r != null && r.usable();
    }

    public static synchronized int cellFootprint(String name) {
        Room r = ROOMS.get(name);
        if (r == null || !r.currentFormat()) {
            return 0;
        }
        return Math.max(1, (r.sizeX - 1) / (TILE + 1)) * Math.max(1, (r.sizeZ - 1) / (TILE + 1));
    }

    /** Tiles across on X, 0 when the room is unknown or not in the current format. */
    public static synchronized int tilesX(String name) {
        Room r = ROOMS.get(name);
        return r == null || !r.currentFormat() ? 0 : Math.max(1, (r.sizeX - 1) / (TILE + 1));
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
     * not a good enough reason to remove something permanently. The shipped copy of the room is used instead.
     */
    private static void quarantine(Path f) {
        try {
            Files.move(f, f.resolveSibling(f.getFileName() + ".corrupt"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            LOGGER.warn("Could not move aside the unreadable room {}", f.getFileName());
        }
    }

    /** How many rooms are finished AND in the current format; the caller holds the lock. */
    private static int completeCountLocked() {
        int n = 0;
        for (Room r : ROOMS.values()) {
            if (r.usable()) {
                n++;
            }
        }
        return n;
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

    private static Room fromJson(JsonObject o) {
        String name = o.get("name").getAsString();
        // The band the FILE was written with, not today's constant. Every capture has carried these two
        // fields all along; reading them is what let the capture floor drop without invalidating a single
        // existing room. A file from before they were written falls back to the old 60..140.
        int fileMinY = o.has("minY") ? o.get("minY").getAsInt() : 60;
        int fileMaxY = o.has("maxY") ? o.get("maxY").getAsInt() : 140;
        Room r = new Room(name, o.get("sizeX").getAsInt(), o.get("sizeZ").getAsInt(), fileMinY, fileMaxY);
        JsonArray pal = o.getAsJsonArray("palette");
        for (int i = 0; i < pal.size(); i++) {
            r.paletteFor(pal.get(i).getAsString());
        }
        // Both formats are read: gzipped "blocksZ" and the old plain number array.
        if (o.has("blocksZ")) {
            decodeShorts(o.get("blocksZ").getAsString(), r.blocks);
        } else if (o.has("blocks")) {
            JsonArray blocks = o.getAsJsonArray("blocks");
            for (int i = 0; i < blocks.size() && i < r.blocks.length; i++) {
                r.blocks[i] = blocks.get(i).getAsShort();
            }
        }
        // Written since 2026-09-29. Older files get the inference that used to live in RoomPlacer, which is
        // correct for every room those files can contain: single-tile rooms are 33 wide and multi-tile ones
        // were captured at the old inflated size, which is also a multiple of 31 plus 2.
        r.margin = o.has("margin")
                ? o.get("margin").getAsInt()
                : (o.get("sizeX").getAsInt() % TILE == WALL_MARGIN * 2 ? WALL_MARGIN : 0);
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
