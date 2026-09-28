package com.killer560.hub.roomsim;

import com.killer560.hub.livemap.DungeonLayout;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Writes down the real numbers the sim is currently guessing at.
 *
 * <p>killer560 (2026-09-28): "if you need any specific numbers, for instance, the door stuff implement loggers
 * into the instance I'm going to use to scan rooms". Several things in the sim are named constants chosen
 * because nobody had measured the real thing - the wither door's size and shape most of all, where
 * {@link SimDoors} assumes three wide by four tall spanning X with the centre at the bottom middle. That
 * assumption was written down honestly rather than hidden, but a guess in the code is still a guess.
 *
 * <p>So while he is scanning rooms anyway, this records what a real wither door is actually made of. It runs
 * only in the recorder instance, appends to its own file, and never writes to the room library - a measurement
 * log is not room data and must not end up shipped as though it were.
 *
 * <p>Each door is logged once per session. A dungeon has several and he will run hundreds of floors; logging
 * every sighting would produce a file nobody reads.
 */
public final class SimMeasure {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-roomsim");

    private static final Path FILE =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-measurements.txt");

    /** How far around the door's own block to look. Generous: the point is to find the edges, not to assume them. */
    private static final int PROBE = 6;

    /** Doors already written this session, by their block position. */
    private static final Set<BlockPos> LOGGED = new HashSet<>();

    private SimMeasure() {
    }

    /**
     * Looks at every wither door on the current map and writes down any that are loaded and not yet recorded.
     *
     * <p>Called from the room recorder's scan, so it costs nothing when the recorder is not running.
     */
    public static void scanDoors(Minecraft client) {
        if (client == null || client.level == null) {
            return;
        }
        DungeonLayout layout = DungeonLayout.current();
        if (layout == null) {
            return;
        }
        for (int idx = 0; idx < DungeonLayout.GRID * DungeonLayout.GRID; idx++) {
            if (layout.doorType(idx) != DungeonLayout.DOOR_WITHER) {
                continue;
            }
            BlockPos door = DungeonLayout.doorBlock(idx);
            if (LOGGED.contains(door)) {
                continue;
            }
            if (!com.killer560.hub.chunkcache.ChunkCacheManager.isLoadedOrCached(client.level, door)) {
                continue;
            }
            LOGGED.add(door);
            write(describe(client.level, door));
        }
    }

    /**
     * A block census around a door.
     *
     * <p>Reports the extent of every non-air block type found, rather than a verdict. A census can be re-read
     * later and re-interpreted; a conclusion written now ("doors are 3x4") would bake in whatever this pass
     * happened to see, which is exactly the mistake being corrected.
     */
    private static String describe(Level level, BlockPos door) {
        StringBuilder sb = new StringBuilder();
        sb.append("WITHER DOOR at ").append(door.getX()).append(' ').append(door.getY()).append(' ')
                .append(door.getZ()).append('\n');
        java.util.Map<String, int[]> extents = new java.util.LinkedHashMap<>();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -PROBE; dx <= PROBE; dx++) {
            for (int dy = -2; dy <= PROBE; dy++) {
                for (int dz = -PROBE; dz <= PROBE; dz++) {
                    cursor.set(door.getX() + dx, door.getY() + dy, door.getZ() + dz);
                    BlockState st = level.getBlockState(cursor);
                    if (st.isAir()) {
                        continue;
                    }
                    String id = BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString();
                    int[] e = extents.computeIfAbsent(id, k -> new int[]{
                        Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE,
                        Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, 0});
                    e[0] = Math.min(e[0], dx);
                    e[1] = Math.max(e[1], dx);
                    e[2] = Math.min(e[2], dy);
                    e[3] = Math.max(e[3], dy);
                    e[4] = Math.min(e[4], dz);
                    e[5] = Math.max(e[5], dz);
                    e[6]++;
                }
            }
        }
        for (var entry : extents.entrySet()) {
            int[] e = entry.getValue();
            sb.append(String.format(Locale.US,
                    "    %-40s count %-5d dx %d..%d  dy %d..%d  dz %d..%d%n",
                    entry.getKey(), e[6], e[0], e[1], e[2], e[3], e[4], e[5]));
        }
        return sb.toString();
    }

    private static void write(String text) {
        try {
            Files.writeString(FILE, "[" + Instant.now() + "]\n" + text + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            LOGGER.warn("Could not write the measurement log", e);
        }
    }

    /** Where the log lives, so the recorder can say so once rather than leaving it to be found. */
    public static Path file() {
        return FILE;
    }
}
