package com.killer560.hub.maplog;

import com.killer560.hub.BuildVariant;
import com.killer560.hub.livemap.DungeonLayout;
import com.killer560.hub.livemap.LiveMapFeature;
import com.killer560.hub.roomsim.RoomLibrary;
import com.killer560.hub.util.ClientChunks;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.ModPaths;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AbstractSkullBlock;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.slf4j.Logger;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The always-on map logger: every skull block in the dungeon room he stands in, written to a log file.
 *
 * <p>killer560 (2026-10-20): "rooms are missing skulls on the sim". The sim's rooms come from captures, and a
 * capture only has the skulls that were there when it was read. Decorative skulls are not secrets: he cannot
 * etherwarp onto them, so the pathfinder treats them as open floor, and a sim room without one sends it through a
 * wall. This logger records the skulls as the room is first seen, so the next capture can be checked against them.
 *
 * <p>What it records, and what it does not:
 * <ul>
 *   <li>Only skull blocks ({@link AbstractSkullBlock}). Nothing else in the room is read, so ordinary blocks, chests
 *       and levers are never written.</li>
 *   <li>A skull is written once, the first time it is seen in a room. A skull that later disappears is written as
 *       "gone" and never goes back into the room's record: that is how a skull the Dungeon Breaker or Breaker Aura
 *       broke (the breaker's target list includes skulls, {@code BreakerAuraFeature}) is kept out of the room.</li>
 *   <li>A skull the player has already broken before the logger first sees its room is never written at all.</li>
 * </ul>
 * Positions are written the way captures store them: x and z from the room's origin, y in the capture's own heights
 * ({@link DungeonLayout#simYOffset()}), so a line can be compared with a capture directly.
 *
 * <p>Dev builds only ({@link BuildVariant#DEV_TOOLS}): a release jar does not write files into friends' folders. The
 * file is {@code killer560smod-maplog-<time>.log} under {@code dungeons/maplog/}; the {@code .log} name keeps it out of
 * profiles. It runs with no setting and no command.
 */
public final class MapLogger {

    private static final Logger LOGGER = ModLog.get("killer560smod-maplog");
    private static final int SCAN_EVERY_TICKS = 10;
    private static final String UNIDENTIFIED = "Unknown";

    /** Skulls already written for each room visit key, with their captured-space text. */
    private static final Map<String, Map<BlockPos, String>> KNOWN = new HashMap<>();
    /** Room keys that have had their first scan written this session. */
    private static final Set<String> SCANNED = new HashSet<>();

    private static int ticks;
    private static BufferedWriter out;

    private MapLogger() {
    }

    public static void register() {
        if (!BuildVariant.DEV_TOOLS) {
            return;
        }
        // Through FeatureGuard so the Performance HUD's stutter line can name it (stutter-1026: his logs show ~250-line
        // bursts from here on entering a room).
        ClientTickEvents.END_CLIENT_TICK.register(com.killer560.hub.util.FeatureGuard.end("MapLogger", client -> {
            if (++ticks % SCAN_EVERY_TICKS != 0) {
                return;
            }
            try {
                scan(client);
            } catch (RuntimeException e) {
                // A logger must never take the client down with it: say so once and keep going.
                LOGGER.error("Map logger scan failed", e);
            }
        }));
    }

    private static void scan(Minecraft client) {
        Level level = client.level;
        if (level == null || client.player == null) {
            KNOWN.clear();
            SCANNED.clear();
            return;
        }
        DungeonLayout layout = DungeonLayout.capture();
        int room = layout.currentRoom();
        String name = room < 0 ? null : layout.name(room);
        if (name == null || UNIDENTIFIED.equals(name)) {
            return;
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
        int sizeX = tilesX * (RoomLibrary.TILE + 1) + 1;
        int sizeZ = tilesZ * (RoomLibrary.TILE + 1) + 1;
        // The same window a room capture reads: one tile half and the wall margin out from the first tile's center.
        int originX = LiveMapFeature.START_X + minGx * 16 - RoomLibrary.TILE / 2 - RoomLibrary.WALL_MARGIN;
        int originZ = LiveMapFeature.START_Z + minGz * 16 - RoomLibrary.TILE / 2 - RoomLibrary.WALL_MARGIN;
        String key = name + "@" + originX + "," + originZ;
        int yOffset = DungeonLayout.simYOffset();

        Map<BlockPos, String> known = KNOWN.computeIfAbsent(key, k -> new HashMap<>());
        Set<BlockPos> found = findSkulls(level, originX, originZ, sizeX, sizeZ);
        if (found == null) {
            // Some chunk of the footprint is not loaded yet: judge nothing this scan, so nothing reads as gone.
            return;
        }
        for (BlockPos pos : found) {
            if (!known.containsKey(pos)) {
                String id = blockId(level, pos);
                known.put(pos, id);
                write(name, originX, originZ, pos, yOffset, id, "seen");
            }
        }
        var gone = new java.util.ArrayList<BlockPos>();
        for (BlockPos pos : known.keySet()) {
            if (!found.contains(pos)) {
                gone.add(pos);
            }
        }
        for (BlockPos pos : gone) {
            String id = known.remove(pos);
            write(name, originX, originZ, pos, yOffset, id, "gone (broken or moved; not part of the room)");
        }
        if (SCANNED.add(key)) {
            write(name, originX, originZ, null, yOffset, "skulls=" + found.size(), "first scan of this room visit");
        }
    }

    /**
     * Every skull block in the footprint, or null when any chunk the footprint touches is not loaded. Reads a
     * section only when its palette holds a skull, so a room of air costs one palette check per section.
     */
    private static Set<BlockPos> findSkulls(Level level, int originX, int originZ, int sizeX, int sizeZ) {
        int minX = originX;
        int maxX = originX + sizeX - 1;
        int minZ = originZ;
        int maxZ = originZ + sizeZ - 1;
        Set<BlockPos> found = new HashSet<>();
        for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
            for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                if (!ClientChunks.isLive(level, cx, cz)) {
                    return null;
                }
                LevelChunk chunk = level.getChunk(cx, cz);
                int x0 = Math.max(minX, cx << 4);
                int x1 = Math.min(maxX, (cx << 4) + 15);
                int z0 = Math.max(minZ, cz << 4);
                int z1 = Math.min(maxZ, (cz << 4) + 15);
                LevelChunkSection[] sections = chunk.getSections();
                for (int i = 0; i < sections.length; i++) {
                    LevelChunkSection section = sections[i];
                    if (section == null || section.hasOnlyAir()
                            || !section.maybeHas(state -> state.getBlock() instanceof AbstractSkullBlock)) {
                        continue;
                    }
                    int sectionMinY = chunk.getSectionYFromSectionIndex(i) << 4;
                    for (int x = x0; x <= x1; x++) {
                        for (int z = z0; z <= z1; z++) {
                            for (int y = sectionMinY; y < sectionMinY + 16; y++) {
                                if (y < level.getMinY() || y > level.getMaxY()) {
                                    continue;
                                }
                                if (section.getBlockState(x & 15, y & 15, z & 15).getBlock() instanceof AbstractSkullBlock) {
                                    found.add(new BlockPos(x, y, z));
                                }
                            }
                        }
                    }
                }
            }
        }
        return found;
    }

    private static String blockId(Level level, BlockPos pos) {
        return BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString();
    }

    private static void write(String room, int originX, int originZ, BlockPos pos, int yOffset, String detail,
                              String what) {
        String where = pos == null ? "" : " at=(" + (pos.getX() - originX) + "," + (pos.getY() - yOffset) + ","
                + (pos.getZ() - originZ) + ") world=(" + pos.getX() + "," + pos.getY() + "," + pos.getZ() + ")";
        String line = "[MapLog] room=" + room + " origin=(" + originX + "," + originZ + ")" + where + " "
                + (pos == null ? "" : detail + " ") + what;
        LOGGER.info(line);
        try {
            if (out == null) {
                open();
            }
            out.write(line);
            out.newLine();
            out.flush();
        } catch (IOException e) {
            LOGGER.warn("Map logger could not write: {}", e.getMessage());
        }
    }

    private static void open() throws IOException {
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        Path file = ModPaths.config("killer560smod-maplog-" + stamp + ".log");
        Files.createDirectories(file.getParent());
        out = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        LOGGER.info("Map log writing to {}", file);
    }
}
