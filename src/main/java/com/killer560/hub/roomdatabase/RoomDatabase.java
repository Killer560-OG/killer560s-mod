package com.killer560.hub.roomdatabase;

import com.google.gson.Gson;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The real dungeon room database (140 known rooms - names, shapes, and secret positions) - killer560's
 * "get the room database from noamm" request, for both the Live Map's room-name identification and real
 * preloaded per-room Secret Waypoints.
 * <p>
 * Downloaded at runtime from {@code https://api.noamm.org/na/data/download} - the exact same public
 * endpoint NoammAddons' own client fetches this from (read directly out of their real
 * {@code DataDownloader.kt}, not guessed); no authentication, so this is the same public data every
 * NoammAddons user's game already downloads, just fetched into this mod's own config folder instead of
 * bundling/redistributing a copy in this repo - it stays current the same way NoammAddons' own copy
 * does, checked against the real version-hash endpoint before re-downloading.
 * <p>
 * Room identification itself uses NoammAddons' own real technique (from {@code ScanUtils.kt}), ported
 * faithfully: hash together the block at every Y level from 140 down to 12 at a room's grid position
 * (skipping a few "cosmetic variance" blocks - chests, pistons, fluids, fire, and any wood plank type -
 * by treating them as air for hashing purposes, since two copies of the same room can differ only in
 * those), then look the hash up against each room's own list of known valid hashes.
 */
public final class RoomDatabase {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-roomdatabase");
    private static final String VERSION_URL = "https://api.noamm.org/na/data/version";
    private static final String DOWNLOAD_URL = "https://api.noamm.org/na/data/download";
    private static final Gson GSON = new Gson();

    private static final Set<String> IGNORED_CORE_BLOCKS = Set.of(
            "minecraft:chest", "minecraft:trapped_chest",
            "minecraft:piston_head", "minecraft:moving_piston",
            "minecraft:water", "minecraft:lava",
            "minecraft:fire", "minecraft:soul_fire");

    private static volatile Map<Integer, RoomEntry> byCoreHash;
    private static volatile boolean loading = false;
    private static final Map<Block, Integer> tokenHashCache = new HashMap<>();

    private RoomDatabase() {
    }

    public static boolean isReady() {
        return byCoreHash != null;
    }

    /** Kicks off a background load if one isn't already running/done - safe to call every tick, it
     *  no-ops once loaded. */
    public static void ensureLoading() {
        if (byCoreHash != null || loading) {
            return;
        }
        loading = true;
        new Thread(RoomDatabase::loadBlocking, "killer560smod-roomdb-load").start();
    }

    public static RoomEntry lookup(int coreHash) {
        Map<Integer, RoomEntry> map = byCoreHash;
        return map == null ? null : map.get(coreHash);
    }

    private static Path dataDir() {
        return FabricLoader.getInstance().getConfigDir().resolve("killer560smod-roomdata");
    }

    private static void loadBlocking() {
        try {
            Path dir = dataDir();
            Path versionFile = dir.resolve("version.txt");
            String remoteHash = fetchText(VERSION_URL);
            String localHash = Files.exists(versionFile) ? Files.readString(versionFile, StandardCharsets.UTF_8).trim() : null;

            if (!remoteHash.equals(localHash) || !Files.exists(dir.resolve("rooms-modern.json"))) {
                LOGGER.info("[RoomDatabase] Downloading real room database (local={}, remote={})...", localHash, remoteHash);
                downloadAndExtract(dir);
                Files.writeString(versionFile, remoteHash, StandardCharsets.UTF_8);
            }

            String json = Files.readString(dir.resolve("rooms-modern.json"), StandardCharsets.UTF_8);
            RoomEntry[] entries = GSON.fromJson(json, RoomEntry[].class);
            Map<Integer, RoomEntry> map = new HashMap<>();
            for (RoomEntry entry : entries) {
                if (entry.cores == null) {
                    continue;
                }
                for (int core : entry.cores) {
                    map.put(core, entry);
                }
            }
            byCoreHash = map;
            LOGGER.info("[RoomDatabase] Loaded {} rooms ({} core hashes).", entries.length, map.size());
        } catch (Exception e) {
            LOGGER.warn("[RoomDatabase] Failed to load room database - room names/secrets will be unavailable this session.", e);
        } finally {
            loading = false;
        }
    }

    private static String fetchText(String url) throws IOException {
        URL u = URI.create(url).toURL();
        try (InputStream in = u.openStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
    }

    private static void downloadAndExtract(Path dir) throws IOException {
        Files.createDirectories(dir);
        Path zipFile = dir.resolve("download.zip");
        URL url = URI.create(DOWNLOAD_URL).toURL();
        try (InputStream in = url.openStream()) {
            Files.copy(in, zipFile, StandardCopyOption.REPLACE_EXISTING);
        }
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(zipFile))) {
            ZipEntry entry;
            String rootPrefix = null;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (rootPrefix == null) {
                    int slash = name.indexOf('/');
                    rootPrefix = slash >= 0 ? name.substring(0, slash + 1) : "";
                }
                String relative = name.startsWith(rootPrefix) ? name.substring(rootPrefix.length()) : name;
                if (relative.isEmpty()) {
                    continue;
                }
                Path target = dir.resolve(relative).normalize();
                if (!target.startsWith(dir)) {
                    continue; // zip-slip guard
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        Files.deleteIfExists(zipFile);
    }

    /** Real room-identification hash, ported directly from NoammAddons' own {@code ScanUtils.getCore} -
     *  same block-token rules, same {@code hash = hash*31 + tokenHash} accumulation, same y=140..12
     *  scan range. Kotlin's {@code String.hashCode()} on the JVM IS {@code java.lang.String.hashCode()},
     *  so this produces byte-for-byte the same hash NoammAddons' own client computes for the same
     *  blocks - no separate algorithm to get subtly wrong. */
    public static int getCore(Level level, int x, int z) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, 0, z);
        int hash = 1;
        for (int y = 140; y >= 12; y--) {
            pos.setY(y);
            Block block = level.getBlockState(pos).getBlock();
            int tokenHash = tokenHashCache.computeIfAbsent(block, RoomDatabase::computeTokenHash);
            hash = hash * 31 + tokenHash;
        }
        return hash;
    }

    private static int computeTokenHash(Block block) {
        String name = BuiltInRegistries.BLOCK.getKey(block).toString();
        if (IGNORED_CORE_BLOCKS.contains(name) || name.endsWith("_planks")) {
            name = "minecraft:air";
        }
        return name.hashCode();
    }

    /** Real corner/rotation detection, ported from NoammAddons' own {@code UniqueRoom.findRotation} -
     *  a room's roof always has a real {@code BLUE_TERRACOTTA} marker block at exactly one of its 4
     *  corners, whose position identifies both the corner (for secret-coordinate translation) and the
     *  room's rotation (corner index * 90 degrees). Scoped down from NoammAddons' own multi-tile
     *  "UniqueRoom" grouping - this checks only the single grid cell's own 32x32 footprint, which is
     *  correct for every 1x1 room and a reasonable approximation for larger ones.
     *  @return {@code [clayX, clayZ, rotationDegrees]}, or null if no corner marker was found (yet). */
    public static int[] findRotationAndCorner(Level level, int roomCenterX, int roomCenterZ, int roofHeight) {
        int h = 15;
        int minX = roomCenterX - h;
        int maxX = roomCenterX + h;
        int minZ = roomCenterZ - h;
        int maxZ = roomCenterZ + h;
        int[][] corners = {
                {minX, minZ}, {maxX, minZ}, {maxX, maxZ}, {minX, maxZ}
        };
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int i = 0; i < 4; i++) {
            pos.set(corners[i][0], roofHeight, corners[i][1]);
            if (level.getBlockState(pos).is(Blocks.BLUE_TERRACOTTA)) {
                return new int[]{corners[i][0], corners[i][1], i * 90};
            }
        }
        return null;
    }

    /** Real relative-to-absolute secret coordinate transform, ported from NoammAddons' own
     *  {@code ScanUtils.getRealCoord}. */
    public static BlockPos toRealCoord(RoomEntry.Pos relative, int clayX, int clayZ, int rotationDegrees) {
        BlockPos rotated = rotate(relative.x, relative.y, relative.z, rotationDegrees);
        return rotated.offset(clayX, 0, clayZ);
    }

    private static BlockPos rotate(int x, int y, int z, int degrees) {
        int normalized = ((degrees % 360) + 360) % 360;
        return switch (normalized) {
            case 90 -> new BlockPos(z, y, -x);
            case 180 -> new BlockPos(-x, y, -z);
            case 270 -> new BlockPos(-z, y, x);
            default -> new BlockPos(x, y, z);
        };
    }
}
