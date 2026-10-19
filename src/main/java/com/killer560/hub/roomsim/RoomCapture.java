package com.killer560.hub.roomsim;

import com.killer560.hub.livemap.DungeonLayout;
import com.killer560.hub.livemap.LiveMapFeature;
import com.killer560.hub.roomdatabase.RoomDatabase;
import com.killer560.hub.roomdatabase.RoomEntry;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ModLog;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Re-captures ONE room from the world he is standing in - {@code /mage} (and {@code /killer560 mage}).
 *
 * <p>killer560 (2026-10-15): "Make the command /mage". The shipped Mage capture is two rooms stitched together: its
 * second tile's centre column hashes to ALTAR's core in the room database, so in the sim Mage has one real doorway and
 * is a dead end (docs/SIM.md, "Doorways are at one height"). The Room Recorder that took captures was removed on
 * 2026-10-04 (tag {@code room-recorder-last}); this restores only its read-and-save half, for one room at a time, with
 * the check that recorder never had: before anything is written, every tile's centre column must hash to THIS room.
 *
 * <p>What it does, in order, refusing with a chat line at the first thing that is wrong:
 * <ol>
 *   <li>the live map's room under him ({@link DungeonLayout#currentRoom}) must be the room asked for, with its tiles
 *       grouped to the room database's shape (a partly walked room is grouped short, and the recorder's worst damage
 *       came from capturing a mis-grouped box - docs/SIM.md);</li>
 *   <li>every chunk the room's footprint touches must be loaded - vanilla loaded, not the Chunk Cache - or the walk
 *       would record air for blocks it never saw;</li>
 *   <li>the whole footprint ({@code tiles * 32 + 1} each way, wall margin included, origin one block outside the first
 *       tile) is read at every y of the level, in WORLD orientation exactly as every shipped capture was:
 *       {@link RoomCaptureRotation} works the turn out from the blocks, so nothing is rotated here. In the sim, y is
 *       taken back to the capture's own heights ({@link SimAltitude#toCaptured});</li>
 *   <li>{@link #validate}: every tile's centre column is this room in the room database, and the room has a doorway at
 *       the doorway floor ({@link RoomDoors#DOOR_FLOOR_Y}, y 69..72);</li>
 *   <li>written to the user library ({@link RoomLibrary#writeCapture}: a file already there is backed up first), read
 *       back and compared block for block, and the library reloaded - a usable disk room wins over the jar's.</li>
 * </ol>
 * It only reads the client world and writes a file: no packet is sent. Dev builds only ({@code BuildVariant.DEV_TOOLS}),
 * like the recorder was: friends have no use for it and a release jar should not carry a writer into the room library.
 */
public final class RoomCapture {

    private static final Logger LOGGER = ModLog.get("killer560smod-roomsim");
    private static final String FEATURE = "Room Capture";
    private static final String UNIDENTIFIED = "Unknown";

    /** The outcome of the last capture, for the testkit. */
    public record Result(boolean saved, String room, String refusal, List<String> problems, String file, String backup,
                         int originX, int originZ, int yOffset, int sizeX, int sizeZ, int doorways) {
    }

    private static volatile Result last;
    /** A saved room whose library reload has not been reported yet. */
    private static volatile String pendingReport;

    private RoomCapture() {
    }

    public static Result lastResult() {
        return last;
    }

    public static void register() {
        if (!com.killer560.hub.BuildVariant.DEV_TOOLS) {
            return;
        }
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> {
            dispatcher.register(ClientCommands.literal("mage")
                    .executes(ctx -> captureCurrent(Minecraft.getInstance(), "Mage") ? 1 : 0));
            // Merged into the existing /killer560 node by Brigadier (same literal, children added).
            dispatcher.register(ClientCommands.literal("killer560")
                    .then(ClientCommands.literal("mage")
                            .executes(ctx -> captureCurrent(Minecraft.getInstance(), "Mage") ? 1 : 0)));
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            String name = pendingReport;
            if (name == null || !RoomLibrary.isReady()) {
                return;
            }
            pendingReport = null;
            reportLoaded(name);
        });
    }

    /**
     * Captures the room he is standing in, which must be {@code expected}. Says everything in chat.
     *
     * @return true when a file was written
     */
    public static boolean captureCurrent(Minecraft client, String expected) {
        try {
            return capture(client, expected);
        } catch (RuntimeException e) {
            LOGGER.error("Room capture of {} failed", expected, e);
            refuse(expected, "The capture failed (" + e.getClass().getSimpleName() + "). Nothing was saved.");
            return false;
        }
    }

    private static boolean capture(Minecraft client, String expected) {
        Level level = client.level;
        if (level == null || client.player == null) {
            return refuse(expected, "Not in a world.");
        }
        RoomDatabase.ensureLoading();
        if (!RoomDatabase.isReady()) {
            return refuse(expected, "The room database has not loaded yet - wait a few seconds and run it again.");
        }
        DungeonLayout layout = DungeonLayout.capture();
        int room = layout.currentRoom();
        String name = layout.name(room);
        if (room < 0 || name == null) {
            return refuse(expected, "You are not in a room the dungeon map knows. Stand inside " + expected
                    + " in a dungeon and run it again.");
        }
        if (UNIDENTIFIED.equals(name)) {
            return refuse(expected, "The map has not identified the room you are in yet. Walk around it and run it "
                    + "again.");
        }
        if (!expected.equalsIgnoreCase(name)) {
            return refuse(expected, "You are in " + name + ", not " + expected + ". Stand inside " + expected
                    + " and run it again.");
        }
        int[] tiles = layout.tiles(room);
        int minGx = Integer.MAX_VALUE;
        int minGz = Integer.MAX_VALUE;
        int maxGx = Integer.MIN_VALUE;
        int maxGz = Integer.MIN_VALUE;
        for (int idx : tiles) {
            minGx = Math.min(minGx, idx % DungeonLayout.GRID);
            minGz = Math.min(minGz, idx / DungeonLayout.GRID);
            maxGx = Math.max(maxGx, idx % DungeonLayout.GRID);
            maxGz = Math.max(maxGz, idx / DungeonLayout.GRID);
        }
        int tilesX = (maxGx - minGx) / 2 + 1;
        int tilesZ = (maxGz - minGz) / 2 + 1;
        String shapeProblem = shapeProblem(name, tilesX, tilesZ, tiles.length);
        if (shapeProblem != null) {
            return refuse(expected, shapeProblem + " Walk through all of " + expected
                    + " so the map shows the whole room, then run it again.");
        }
        int sizeX = tilesX * (RoomLibrary.TILE + 1) + 1;
        int sizeZ = tilesZ * (RoomLibrary.TILE + 1) + 1;
        // The first tile's centre, then half a tile and the wall margin out: the window every capture uses.
        int originX = LiveMapFeature.START_X + minGx * 16 - RoomLibrary.TILE / 2 - RoomLibrary.WALL_MARGIN;
        int originZ = LiveMapFeature.START_Z + minGz * 16 - RoomLibrary.TILE / 2 - RoomLibrary.WALL_MARGIN;

        // Every chunk the footprint touches, loaded for real. Asked of the CHUNK SOURCE, never the level:
        // ClientLevel.hasChunk is hard-wired to return true (javap 26.1.2), and a chunk the client does not have reads
        // as an empty chunk - all air - so asking the level would capture a missing chunk as a hole in the room.
        // ClientChunks.isLive is ChunkSource.hasChunk, vanilla's answer even with the Chunk Cache on
        // (ChunkCacheChunkSourceMixin): a capture must not be read from a cached snapshot that may be stale.
        List<String> unloaded = new ArrayList<>();
        int chunks = 0;
        for (int cx = originX >> 4; cx <= (originX + sizeX - 1) >> 4; cx++) {
            for (int cz = originZ >> 4; cz <= (originZ + sizeZ - 1) >> 4; cz++) {
                chunks++;
                if (!com.killer560.hub.util.ClientChunks.isLive(level, cx, cz)) {
                    unloaded.add(towards(client, (cx << 4) + 8, (cz << 4) + 8));
                }
            }
        }
        if (!unloaded.isEmpty()) {
            return refuse(expected, unloaded.size() + " of the " + chunks + " chunks " + expected
                    + " covers are not loaded (" + String.join(", ", new java.util.TreeSet<>(unloaded))
                    + " of you). Walk around the room until all of it has loaded, then run it again.");
        }

        int yOffset = DungeonLayout.simYOffset();
        int levelMin = level.getMinY();
        int levelMax = level.getMaxY();
        RoomLibrary.Room r = RoomLibrary.newCapture(name, sizeX, sizeZ, levelMin - yOffset, levelMax - yOffset);
        r.margin = RoomLibrary.WALL_MARGIN;
        read(level, r, originX, originZ, yOffset);

        // The room's tiles, in the capture's own tile coordinates.
        List<int[]> localTiles = new ArrayList<>();
        for (int idx : tiles) {
            localTiles.add(new int[]{(idx % DungeonLayout.GRID - minGx) / 2, (idx / DungeonLayout.GRID - minGz) / 2});
        }
        List<String> problems = validate(r, name, localTiles);
        int doorways = RoomDoors.measure(r).edges().size();
        if (!problems.isEmpty()) {
            for (String p : problems) {
                ModChat.send(FEATURE, ModChat.bad(p));
            }
            ModChat.send(FEATURE, ModChat.text("Nothing was saved."));
            last = new Result(false, name, "validation", problems, null, null, originX, originZ, yOffset, sizeX, sizeZ,
                    doorways);
            LOGGER.warn("Room capture of {} rejected: {}", name, problems);
            return false;
        }

        RoomLibrary.Written written;
        try {
            written = RoomLibrary.writeCapture(r);
        } catch (java.io.IOException e) {
            LOGGER.error("Could not write the capture of {}", name, e);
            return refuse(expected, "Could not write the file (" + e.getClass().getSimpleName() + "). Nothing was "
                    + "saved.");
        }
        // Read back the way the library will read it, and compare every block.
        String mismatch = roundTrip(r, written.file());
        if (mismatch != null) {
            LOGGER.error("Capture of {} did not read back: {}", name, mismatch);
            ModChat.send(FEATURE, ModChat.bad("Saved, but the file does not read back the same: " + mismatch
                    + ". Do not trust it; tell Claude."));
        }
        last = new Result(mismatch == null, name, mismatch, problems, written.file().toAbsolutePath().toString(),
                written.backup() == null ? null : written.backup().toAbsolutePath().toString(), originX, originZ,
                yOffset, sizeX, sizeZ, doorways);
        ModChat.send(FEATURE, ModChat.good("Captured " + name + " "),
                ModChat.dim("(" + tilesX + "x" + tilesZ + " tiles, " + sizeX + "x" + sizeZ + " blocks, y "
                        + r.contentMinY() + ".." + r.contentMaxY() + ", " + doorways + " doorway(s) at y "
                        + RoomDoors.DOOR_FLOOR_Y + "; every tile's center column is " + name + ")."));
        ModChat.send(FEATURE, ModChat.text("Saved to "), ModChat.value(written.file().toAbsolutePath().toString()));
        if (written.backup() != null) {
            ModChat.send(FEATURE, ModChat.text("The file it replaced is backed up as "),
                    ModChat.value(written.backup().getFileName().toString()));
        }
        ModChat.send(FEATURE, ModChat.text("The sim uses it from the next floor or room it loads (it wins over the copy "
                + "shipped in the jar)."));
        LOGGER.info("Room capture of {} saved to {} (origin {},{}, y offset {}, {}x{}, y {}..{}, {} doorway(s)), "
                        + "backup {}", name, written.file(), originX, originZ, yOffset, sizeX, sizeZ, r.contentMinY(),
                r.contentMaxY(), doorways, written.backup());
        pendingReport = name;
        RoomLibrary.forceReload();
        return true;
    }

    /** Reads every block of the footprint at every y of the level into {@code r}. */
    private static void read(Level level, RoomLibrary.Room r, int originX, int originZ, int yOffset) {
        short air = r.paletteFor("minecraft:air");
        int bottomSection = level.getMinSectionY();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = 0; x < r.sizeX; x++) {
            for (int z = 0; z < r.sizeZ; z++) {
                int wx = originX + x;
                int wz = originZ + z;
                var sections = level.getChunk(wx >> 4, wz >> 4).getSections();
                for (int wy = level.getMinY(); wy <= level.getMaxY(); wy++) {
                    int capturedY = wy - yOffset;
                    int section = (wy >> 4) - bottomSection;
                    if (section >= 0 && section < sections.length && sections[section] != null
                            && sections[section].hasOnlyAir()) {
                        r.setBlockAt(r.index(x, capturedY, z), air);
                        continue;
                    }
                    cursor.set(wx, wy, wz);
                    BlockState state = level.getBlockState(cursor);
                    // The FULL state, as the recorder stored it: a stair's facing is part of the room.
                    r.setBlockAt(r.index(x, capturedY, z), r.paletteFor(
                            net.minecraft.commands.arguments.blocks.BlockStateParser.serialize(state)));
                }
                r.seenColumn[z * r.sizeX + x] = true;
            }
        }
    }

    /**
     * Why {@code room} is not a sound capture of {@code expected}, one line per problem; empty when it is.
     *
     * @param tiles the room's tiles as {@code {tx, tz}} in the capture's own tile grid (an L leaves its missing
     *              quarter out)
     */
    public static List<String> validate(RoomLibrary.Room room, String expected, List<int[]> tiles) {
        List<String> problems = new ArrayList<>();
        if (!room.currentFormat()) {
            problems.add(room.sizeX + "x" + room.sizeZ + " is not a whole number of tiles with the wall margin.");
            return problems;
        }
        if (!room.complete()) {
            problems.add(String.format(Locale.ROOT, "Only %.0f%% of the room's columns were read.",
                    room.completeness() * 100));
        }
        int tilesX = RoomLShape.tiles(room.sizeX);
        int tilesZ = RoomLShape.tiles(room.sizeZ);
        String shape = shapeProblem(expected, tilesX, tilesZ, tiles.size());
        if (shape != null) {
            problems.add(shape);
        }
        for (int[] t : tiles) {
            int x = room.margin + RoomLibrary.TILE / 2 + t[0] * (RoomLibrary.TILE + 1);
            int z = room.margin + RoomLibrary.TILE / 2 + t[1] * (RoomLibrary.TILE + 1);
            int core = RoomDatabase.coreOfColumn(y -> {
                short id = room.at(x, y, z);
                return id >= 0 && id < room.palette.size() ? room.palette.get(id) : null;
            });
            RoomEntry hit = RoomDatabase.lookup(core);
            String where = "Tile " + t[0] + "," + t[1] + " (capture x " + x + ", z " + z + ")";
            if (hit == null) {
                problems.add(where + ": its center column matches no room in the room database.");
            } else if (!expected.equalsIgnoreCase(hit.name)) {
                problems.add(where + ": its center column is " + hit.name + " in the room database, not "
                        + expected + " - another room stitched in.");
            }
        }
        if (RoomDoors.measure(room).edges().isEmpty()) {
            problems.add("No doorway at y " + RoomDoors.DOOR_FLOOR_Y + ".." + (RoomDoors.DOOR_FLOOR_Y + 3)
                    + " anywhere on the room's walls.");
        }
        return problems;
    }

    /** {@link #validate} for a room already in the library, over all its tiles (an L's missing quarter left out). */
    public static List<String> validateLibraryRoom(String name) {
        RoomLibrary.Room room = RoomLibrary.get(name);
        if (room == null) {
            return List.of("No capture called " + name + ".");
        }
        int[] missing = RoomLShape.missingTile(room);
        List<int[]> tiles = new ArrayList<>();
        for (int tx = 0; tx < RoomLShape.tiles(room.sizeX); tx++) {
            for (int tz = 0; tz < RoomLShape.tiles(room.sizeZ); tz++) {
                if (missing == null || missing[0] != tx || missing[1] != tz) {
                    tiles.add(new int[]{tx, tz});
                }
            }
        }
        return validate(room, name, tiles);
    }

    /** Null when the tile box fits the room database's shape for {@code name}, otherwise why not. */
    private static String shapeProblem(String name, int tilesX, int tilesZ, int tileCount) {
        RoomEntry entry = RoomDatabase.lookupByName(name);
        if (entry == null || entry.shape == null) {
            return "The room database has no shape for " + name + ".";
        }
        String shape = entry.shape.trim();
        int want = RoomDatabase.shapeTileCount(shape);
        int a;
        int b;
        if (shape.equalsIgnoreCase("L")) {
            a = 2;
            b = 2;
        } else {
            int cross = shape.toLowerCase(Locale.ROOT).indexOf('x');
            try {
                a = Integer.parseInt(shape.substring(0, cross).trim());
                b = Integer.parseInt(shape.substring(cross + 1).trim());
            } catch (RuntimeException e) {
                return "The room database's shape for " + name + " (" + shape + ") cannot be read.";
            }
        }
        boolean fits = (tilesX == a && tilesZ == b) || (tilesX == b && tilesZ == a);
        if (!fits || tileCount != want) {
            return "The map has " + name + " as " + tileCount + " tile(s) in a " + tilesX + "x" + tilesZ
                    + " box; the room database says " + shape + ".";
        }
        return null;
    }

    /** Null when the file reads back block for block as {@code r}, otherwise the first difference. */
    private static String roundTrip(RoomLibrary.Room r, Path file) {
        RoomLibrary.Room back = RoomLibrary.readFile(file);
        if (back == null) {
            return "the file cannot be read";
        }
        if (back.sizeX != r.sizeX || back.sizeZ != r.sizeZ || back.margin != r.margin) {
            return "size " + back.sizeX + "x" + back.sizeZ + " margin " + back.margin;
        }
        for (int y = r.minY; y <= r.maxY; y++) {
            for (int z = 0; z < r.sizeZ; z++) {
                for (int x = 0; x < r.sizeX; x++) {
                    String want = name(r, r.at(x, y, z));
                    String got = name(back, back.at(x, y, z));
                    // Outside the trimmed band a file holds nothing, which is air - the trim only drops empty layers.
                    if (got == null) {
                        got = "minecraft:air";
                    }
                    if (want.endsWith("_air")) {
                        want = "minecraft:air";
                    }
                    if (got.endsWith("_air")) {
                        got = "minecraft:air";
                    }
                    if (!want.equals(got)) {
                        return "at " + x + "," + y + "," + z + " " + want + " became " + got;
                    }
                }
            }
        }
        for (int i = 0; i < back.seenColumn.length; i++) {
            if (!back.seenColumn[i]) {
                return "column " + i + " reads back as never seen";
            }
        }
        return null;
    }

    private static String name(RoomLibrary.Room r, short id) {
        return id >= 0 && id < r.palette.size() ? r.palette.get(id) : null;
    }

    /** Which way from the player a point lies, as a compass word. */
    private static String towards(Minecraft client, int x, int z) {
        double dx = x - client.player.getX();
        double dz = z - client.player.getZ();
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx >= 0 ? "east" : "west";
        }
        return dz >= 0 ? "south" : "north";
    }

    private static boolean refuse(String expected, String why) {
        ModChat.send(FEATURE, ModChat.bad("Not captured: "), ModChat.text(why));
        last = new Result(false, expected, why, List.of(), null, null, 0, 0, 0, 0, 0, 0);
        LOGGER.info("Room capture of {} refused: {}", expected, why);
        return false;
    }

    /** After the reload: is the room he just saved the one the library holds, and will the sim place it? */
    private static void reportLoaded(String name) {
        RoomLibrary.Room r = RoomLibrary.get(name);
        if (r == null || r.isBundled()) {
            ModChat.send(FEATURE, ModChat.bad("The library reloaded without your capture of " + name
                    + " - the shipped copy is still the one used. Tell Claude."));
            LOGGER.warn("After reload, {} is {}", name, r == null ? "missing" : "the bundled copy");
            return;
        }
        if (!r.usable()) {
            ModChat.send(FEATURE, ModChat.bad("Reloaded, but the sim will not place " + name + ": "
                    + (r.corruptReason != null ? r.corruptReason : "not usable") + ". Tell Claude."));
            LOGGER.warn("After reload, {} is not usable: {}", name, r.corruptReason);
            return;
        }
        RoomCaptureRotation.Verdict v = RoomCaptureRotation.verdict(r);
        ModChat.send(FEATURE, ModChat.good("Library reloaded: the sim now uses your " + name + "."),
                ModChat.dim(v == null ? "" : " Capture rotation " + v.degrees() + (v.uncertain() ? " (uncertain)" : "")
                        + "."));
        LOGGER.info("After reload, {} is the disk capture, usable, rotation {}", name, v == null ? "?" : v);
    }
}
