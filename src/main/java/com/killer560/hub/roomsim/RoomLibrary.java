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
 * and this is where they land. Once a room is complete it never needs capturing again.
 *
 * <p><b>The finished library ships inside the jar</b> ({@link #BUNDLED_ROOT}), so the sim works the same in
 * every instance instead of being only as good as what that instance happened to walk through. His own captures
 * still win over the shipped copies - see {@link #mergeBundled} for the exact rule.
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

    /**
     * Rooms changed since the last write.
     *
     * <p>{@link #saveAll()} rewrites every room - 47 files and about 13 MB of gzipped block arrays - which is
     * fine at the end of a run and ruinous per tick. The capture loop saves as it goes so a crash cannot lose a
     * session, and on 2026-09-29 that meant re-serialising the whole library twenty times a second on the
     * render thread, under this class's own lock. {@link #saveDirty()} writes only what actually changed;
     * {@code capture} is the one thing that changes a room, so this stays honest as long as that is true.
     */
    private static final java.util.Set<String> DIRTY = new java.util.LinkedHashSet<>();
    private static boolean loaded;

    /**
     * Marks a room as changed, and therefore no longer the copy that shipped in the jar.
     *
     * <p>Both halves in one place on purpose. {@link Room#fromJar} is what keeps {@link #saveAll()} from
     * writing the shipped baseline into every instance, so a write path that forgot to clear it would make his
     * own change look like a shipped room and quietly refuse to save it.
     */
    private static void markDirty(Room r) {
        DIRTY.add(r.name);
        r.fromJar = false;
    }

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
         * <p>Exists so {@link RoomLibrary#saveAll()} does not write 135 shipped rooms into every instance's
         * config folder. {@code saveAll} rewrites the whole library - about 13 MB of gzipped block arrays -
         * and copying the jar's own baseline out to disk would make that both bigger and pointless, and would
         * then be indistinguishable from something he captured himself.
         *
         * <p>Cleared by {@link RoomLibrary#markDirty} the moment anything changes the room, which is every
         * path that writes to it: a capture that fills columns, a mob spawn, a synthetic {@code set}. So
         * "bundled" always means "still byte-for-byte what shipped", and anything else gets saved normally.
         * A re-capture at a different footprint builds a brand new {@link Room} anyway (see
         * {@link RoomLibrary#captureAt}), which starts out false.
         */
        boolean fromJar;

        /** Whether this room is the untouched copy that shipped in the jar. */
        public boolean isBundled() {
            return fromJar;
        }

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
         * <p>A room stored at an older footprint is not a room this mod can use: {@code capture} throws it away
         * and rebuilds it the moment it is seen again, and {@code RoomPlacer} would paste it at the wrong size.
         * So it must not be counted as done anywhere, or the progress numbers promise work that still has to be
         * redone - which is exactly what happened on 2026-09-29, when 49 of 102 "complete" rooms turned out to
         * be at the old inflated size.
         *
         * <p>Every valid footprint is {@code tiles * 32 + 1} - see {@link RoomLibrary#footprint}.
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
            int air = palette.indexOf("minecraft:air");
            int lo = MAX_Y;
            int hi = MIN_Y;
            boolean any = false;
            for (int y = MIN_Y; y <= MAX_Y; y++) {
                boolean solid = false;
                for (int z = 0; z < sizeZ && !solid; z++) {
                    for (int x = 0; x < sizeX; x++) {
                        short id = blocks[index(x, y, z)];
                        if (id >= 0 && id != air) {
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

        /** Finished AND in the current format - the only thing that should ever be called done. */
        public boolean usable() {
            return currentFormat() && complete();
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
     * is synchronized too, so the render thread calling {@link #completeCount()} to draw the menu blocked
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
        int onDisk = fresh.size();
        // Still no lock held. mergeBundled reads the jar and merges into the same local map.
        int rescued = mergeBundled(fresh);
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
                        + "{} bundled room(s) replaced an unusable local copy, {} complete, "
                        + "{} upgraded to the compact format, {} ms",
                fresh.size(), fromJar, fresh.size() - fromJar, onDisk, rescued, complete, upgraded,
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
     *   <li>If NEITHER is usable the disk one stays, so a half-captured room keeps its partial progress and the
     *       Room Recorder can carry on filling it in.</li>
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
                    continue; // his own capture wins; do not even decode the shipped blocks
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

    /**
     * How many grid cells a captured room covers, as {@code tilesX * tilesZ}.
     *
     * <p>The inverse of {@link #footprint}, in one place, because the layout planner and the paste MUST agree
     * about it. They did not between 00:27 and 01:20 on 2026-09-29 and the result was every multi-tile room
     * pasted over its neighbour.
     *
     * @return 0 when the room is unknown or not in the current format
     */
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
            if (r.usable()) {
                n++;
            }
        }
        return n;
    }

    /** How many rooms are finished AND in the current format. */
    public static synchronized int completeCount() {
        return completeCountLocked();
    }

    /**
     * How many rooms there are to capture in total.
     *
     * <p>killer560 (2026-09-29): "can you update the room scanner and recorder and all that to have the proper
     * number of rooms." Every progress line used {@link #roomCount()} - the number of FILES ON DISK - as the
     * denominator, so it read "36/47 rooms complete" when the real figure was 36 of 140 and the denominator
     * grew every time a new room was found. A fraction whose bottom half moves is not progress.
     *
     * <p>The real total is the room database, the same 140-room list the Live Map identifies rooms against and
     * the missing-rooms HUD already compares to. Falls back to the file count only while that is still loading,
     * so the number is never zero and never pretends to be authoritative when it is not.
     *
     * <p><b>Unchanged in meaning now that 135 rooms ship with the mod, and that is deliberate.</b> The
     * denominator is still "every room Catacombs has", not "every room this instance could have" - so
     * "135 of 140 rooms complete" now reads the same in every instance and says what is actually true: the
     * shipped baseline covers all but a handful, and those few are still worth capturing. Making it the shipped
     * count instead would read "135 of 135" everywhere and hide the remaining work; making it the file count
     * would put a moving denominator back, which is the bug this method was written to fix.
     *
     * <p>Never smaller than {@link #roomCount()}. {@link #completeCount()} can only count rooms that are in the
     * map, so a library holding a room the database has not heard of - a re-capture under a slightly different
     * name, say - could otherwise print "137 of 135".
     */
    public static int expectedCount() {
        if (!com.killer560.hub.roomdatabase.RoomDatabase.isReady()) {
            return roomCount();
        }
        java.util.Set<String> names = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (com.killer560.hub.roomdatabase.RoomEntry e
                : com.killer560.hub.roomdatabase.RoomDatabase.allEntries()) {
            if (e != null && e.name != null && !e.name.isBlank()) {
                names.add(e.name);
            }
        }
        return Math.max(names.size(), roomCount());
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
    /**
     * How much of the floor in front of him is already in the library.
     *
     * <p>killer560 (2026-09-29): "does it send a chat message once I can go to a new map". It did not - it only
     * reported a running column count, which says the capture is working but never says it is finished, so the
     * only way to know a floor was done was to guess. This is the number that answers it.
     *
     * <p>A room counts as done when a Room of the RIGHT SIZE exists and every one of its columns has been seen.
     * The size test matters: a room captured at another footprint is a different room as far as capture is
     * concerned, and it rebuilds rather than merges, so calling that one done would skip a room that is about
     * to be re-read from scratch.
     *
     * @return {@code {done, total}} over the rooms this layout has identified; a room still sitting on the
     *         "Unknown" placeholder is counted in neither, because it cannot be captured yet either.
     */
    public static synchronized int[] floorProgress(DungeonLayout layout) {
        load();
        int done = 0;
        int total = 0;
        if (layout == null) {
            return new int[]{0, 0};
        }
        for (int room = 0; room < layout.roomCount(); room++) {
            String name = layout.name(room);
            if (name == null || name.isBlank() || UNIDENTIFIED.equals(name)) {
                continue;
            }
            int[] tiles = layout.tiles(room);
            if (tiles == null || tiles.length == 0) {
                continue;
            }
            total++;
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
            int sizeX = footprint(maxGx - minGx);
            int sizeZ = footprint(maxGz - minGz);
            Room r = ROOMS.get(name);
            if (r != null && r.sizeX == sizeX && r.sizeZ == sizeZ && r.usable()) {
                done++;
            }
        }
        return new int[]{done, total};
    }

    /**
     * How wide a room is, from how many GRID CELLS its tiles span on one axis.
     *
     * <p>Wrong until 2026-09-29, and wrong in a way that only showed up on multi-tile rooms. Cells are
     * {@code HALF_ROOM} = 16 blocks apart and a room's tiles sit on every OTHER cell, so a two-tile room spans
     * 2 cells and a three-tile room spans 4 - the cells in between are the same room, not extra tiles. The old
     * formula read the cell span as a tile count and multiplied it by 31, so a three-tile room was captured
     * 157 blocks long where the room is 97. The extra 60 columns ran into the NEXT room, which is why Gravel
     * and Diagonal sat at 5-10% and why pasting one into the sim would have overwritten its neighbour.
     *
     * <p>A 1x1 room spans 0 cells and comes out at 33 either way, which is exactly why this survived: every
     * single-tile room in the library is correct and they are the majority.
     *
     * <p>The room itself is {@code tiles * TILE} plus the one-block seam between each pair of tiles, and then
     * {@link #WALL_MARGIN} on each side: 3 tiles = 93 + 2 + 2 = 97.
     */
    static int footprint(int cellSpan) {
        int tiles = cellSpan / 2 + 1;
        return tiles * TILE + (tiles - 1) + WALL_MARGIN * 2;
    }

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
        return captureBox(level, name, minGx, minGz, maxGx, maxGz, columnBudget);
    }

    /**
     * Captures one named room occupying the grid cells {@code minG..maxG}, with no {@link DungeonLayout}.
     *
     * <p>Split out of {@link #capture} on 2026-09-29 for Ashfall's single-room practice worlds. Those put one
     * room in an empty world and a sidebar saying {@code Practice Room}, so there is no floor, no layout and no
     * room list - but the room still sits on the ordinary dungeon grid, so everything from the footprint down
     * is identical and belongs in one place rather than copied.
     */
    public static synchronized int captureBox(Level level, String name, int minGx, int minGz,
                                              int maxGx, int maxGz, int columnBudget) {
        BlockPos origin = DungeonLayout.cellCenter(minGz * DungeonLayout.GRID + minGx);
        return captureAt(level, name, origin.getX(), origin.getZ(),
                (maxGx - minGx) / 2 + 1, (maxGz - minGz) / 2 + 1, columnBudget);
    }

    /**
     * Captures a room by the WORLD position of its first tile's centre, with no grid at all.
     *
     * <p>{@link DungeonLayout} is a fixed 11x11 window anchored at world -185, which is every real Catacombs
     * floor and is not everything worth capturing. killer560 (2026-09-29) found an Ashfall preset holding all
     * 134 rooms in a line spanning 47 cells; the grid could see five columns of it, reported "all 16 rooms
     * here are fully captured" and was telling the truth about the only rooms it could see. Walking does not
     * help, because the window is anchored to the world and not to him.
     *
     * <p>So this takes world coordinates. Everything below the footprint was already independent of the grid;
     * only the origin ever needed it.
     *
     * @param centreX  world X of the centre of the room's lowest-X tile
     * @param centreZ  world Z of the centre of the room's lowest-Z tile
     * @param tilesX   how many tiles wide, at least 1
     * @param tilesZ   how many tiles deep, at least 1
     */
    public static synchronized int captureAt(Level level, String name, int centreX, int centreZ,
                                             int tilesX, int tilesZ, int columnBudget) {
        load();
        if (columnBudget <= 0 || name == null || name.isBlank() || UNIDENTIFIED.equals(name)
                || tilesX < 1 || tilesZ < 1) {
            return 0;
        }
        int sizeX = footprint((tilesX - 1) * 2);
        int sizeZ = footprint((tilesZ - 1) * 2);
        Room r = ROOMS.get(name);
        if (r == null || r.sizeX != sizeX || r.sizeZ != sizeZ) {
            r = new Room(name, sizeX, sizeZ);
            ROOMS.put(name, r);
        }
        int worldX0 = centreX - TILE / 2 - WALL_MARGIN;
        int worldZ0 = centreZ - TILE / 2 - WALL_MARGIN;

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
                    markDirty(r);
                    return added;
                }
            }
        }
        if (added > 0) {
            markDirty(r);
        }
        if (captureMobs(level, r, worldX0, worldZ0)) {
            markDirty(r);
        }
        return added;
    }

    /** At most this many mobs remembered per room - a room is not a mob farm, and the file stays small. */
    private static final int MAX_MOB_SPAWNS = 40;

    /**
     * Records the mobs standing in this room, so a sim floor is not an empty building.
     *
     * <p>{@code Room.mobSpawns} was read by {@code SimBuilder} and written by NOTHING - the field existed, was
     * saved, was loaded, and was empty in all 135 rooms. So every sim floor has been silent and empty, which
     * is a large part of why it does not feel like a dungeon.
     *
     * <p>Honest about what this is: a SNAPSHOT of where mobs happened to be standing when the room was
     * scanned, not where Hypixel spawns them. Dungeon mobs wander, so the positions are approximate. That is
     * still far better than nothing, and it is the only source available - no public dataset carries mob
     * positions either.
     *
     * <p>Only fills up to {@link #MAX_MOB_SPAWNS} and never re-records a position it already has, so scanning
     * the same room for a minute does not accumulate a smear of one zombie's walk.
     *
     * @return true when something new was recorded
     */
    private static boolean captureMobs(Level level, Room r, int worldX0, int worldZ0) {
        if (r.mobSpawns.size() >= MAX_MOB_SPAWNS) {
            return false;
        }
        // Only for the room he is actually near.
        //
        // Without this it is one entity query per room per tick - up to 36 a tick while the recorder sweeps a
        // floor - and a room that simply has no mobs never stops asking. Entities only exist near the player
        // anyway, so a far room could only ever answer "none": the query would cost something and learn
        // nothing. 64 blocks is two rooms out.
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc == null || mc.player == null) {
            return false;
        }
        double cx = worldX0 + r.sizeX / 2.0;
        double cz = worldZ0 + r.sizeZ / 2.0;
        if (mc.player.distanceToSqr(cx, mc.player.getY(), cz) > 64 * 64) {
            return false;
        }
        net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(
                worldX0, MIN_Y, worldZ0, worldX0 + r.sizeX, MAX_Y, worldZ0 + r.sizeZ);
        boolean changed = false;
        for (net.minecraft.world.entity.Entity e
                : level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, box)) {
            String kind = kindOf(e);
            if (kind == null) {
                continue;
            }
            int lx = net.minecraft.util.Mth.floor(e.getX()) - worldX0;
            int ly = net.minecraft.util.Mth.floor(e.getY());
            int lz = net.minecraft.util.Mth.floor(e.getZ()) - worldZ0;
            if (lx < 0 || lz < 0 || lx >= r.sizeX || lz >= r.sizeZ || ly < MIN_Y || ly > MAX_Y) {
                continue;
            }
            String line = lx + "," + ly + "," + lz + "," + kind;
            if (r.mobSpawns.add(line)) {
                changed = true;
                if (r.mobSpawns.size() >= MAX_MOB_SPAWNS) {
                    break;
                }
            }
        }
        return changed;
    }

    /** The {@code SimMobs.Kind} name for an entity, or null for anything the sim cannot stand in for. */
    private static String kindOf(net.minecraft.world.entity.Entity e) {
        if (e instanceof net.minecraft.world.entity.player.Player) {
            return null;
        }
        if (e instanceof net.minecraft.world.entity.ambient.Bat) {
            return "BAT";
        }
        if (e instanceof net.minecraft.world.entity.monster.EnderMan) {
            return "FEL";
        }
        // The 26.1.2 packages: monster.skeleton.* and monster.zombie.*, not monster.* directly.
        if (e instanceof net.minecraft.world.entity.monster.skeleton.AbstractSkeleton) {
            return "SKELETON";
        }
        if (e instanceof net.minecraft.world.entity.monster.zombie.Zombie) {
            return "ZOMBIE";
        }
        return null;
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
        if (r.mobSpawns.add(localX + "," + localY + "," + localZ + "," + kind)) {
            // A shipped room that now holds a spawn he recorded is no longer the shipped room, and saveAll
            // skips anything still flagged as bundled - so without this the spawn would be lost on restart.
            markDirty(r);
        }
    }

    /**
     * Writes every room that is not still the untouched copy from the jar.
     *
     * <p>The skip is not an optimisation, it is the difference between shipping a baseline and copying it into
     * 135 files in every one of his seven instances. {@code saveAll} already rewrites the whole library - about
     * 13 MB of gzipped block arrays - and writing out the jar's own rooms as well would make that worse for no
     * gain, and would leave rooms on disk that look exactly like something he captured.
     *
     * @return how many files were written
     */
    public static synchronized int saveAll() {
        int written = 0;
        try {
            Files.createDirectories(DIR);
            for (Room r : ROOMS.values()) {
                if (r.fromJar) {
                    continue;
                }
                Path f = DIR.resolve(safeName(r.name) + ".json");
                Files.writeString(f, GSON.toJson(toJson(r)), StandardCharsets.UTF_8);
                written++;
            }
            DIRTY.clear();
        } catch (Exception e) {
            LOGGER.error("Could not save the room library", e);
        }
        return written;
    }

    /**
     * Writes only the rooms that changed. For the capture loop, which saves constantly.
     *
     * @return how many files were written, so a caller can skip announcing a save that wrote nothing
     */
    public static synchronized int saveDirty() {
        if (DIRTY.isEmpty()) {
            return 0;
        }
        int written = 0;
        try {
            Files.createDirectories(DIR);
            for (String name : DIRTY) {
                Room r = ROOMS.get(name);
                if (r == null || r.fromJar) {
                    // fromJar here means a reload replaced the room this name was dirty for with the shipped
                    // copy, so the change it refers to no longer exists in memory. Writing the jar's room out
                    // under his name would be worse than writing nothing.
                    continue;
                }
                Path f = DIR.resolve(safeName(r.name) + ".json");
                Files.writeString(f, GSON.toJson(toJson(r)), StandardCharsets.UTF_8);
                written++;
            }
            DIRTY.clear();
        } catch (Exception e) {
            LOGGER.error("Could not save the changed rooms", e);
        }
        return written;
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
        o.addProperty("margin", r.margin);
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
