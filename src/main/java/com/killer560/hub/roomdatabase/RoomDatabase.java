package com.killer560.hub.roomdatabase;

import com.killer560.hub.chunkcache.ChunkCacheManager;
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
import java.util.concurrent.atomic.AtomicBoolean;
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

    private static final long BASE_RETRY_BACKOFF_MS = 30_000L;
    private static final long MAX_RETRY_BACKOFF_MS = 10 * 60_000L;

    private static volatile Map<Integer, RoomEntry> byCoreHash;
    private static final AtomicBoolean loading = new AtomicBoolean(false);
    private static int loadAttempts = 0;
    private static volatile int consecutiveFailures = 0;
    private static volatile long nextAttemptAtMs = 0;
    private static final Map<Block, Integer> tokenHashCache = new HashMap<>();

    private RoomDatabase() {
    }

    public static boolean isReady() {
        return byCoreHash != null;
    }

    /** Kicks off a background load if one isn't already running/done - safe to call every tick, it
     *  no-ops once loaded.
     *  <p>Real bug found and fixed (2026-09-14, code review): a failed load used to retry on the very
     *  next call - i.e. EVERY client tick (callers invoke this before their own 250ms scan throttle),
     *  each spawning a new thread, a new HTTP fetch, and a new stack trace in the log. Now at most one
     *  load is ever in flight (compare-and-set) and failures back off 30s, 60s, 120s... capped at 10min. */
    public static void ensureLoading() {
        if (byCoreHash != null || System.currentTimeMillis() < nextAttemptAtMs) {
            return;
        }
        if (!loading.compareAndSet(false, true)) {
            return;
        }
        loadAttempts++;
        LOGGER.info("[RoomDatabase] Starting background load attempt #{} (consecutiveFailures={}, dataDir={}, rooms-modern.json present={})",
                loadAttempts, consecutiveFailures, dataDir(), Files.exists(dataDir().resolve("rooms-modern.json")));
        Thread thread = new Thread(RoomDatabase::loadBlocking, "killer560smod-roomdb-load");
        thread.setDaemon(true);
        thread.start();
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
            String localHash = Files.exists(versionFile) ? Files.readString(versionFile, StandardCharsets.UTF_8).trim() : null;
            String remoteHash;
            boolean cachedFallback = false;
            try {
                remoteHash = fetchText(VERSION_URL);
            } catch (IOException e) {
                // 2026-09-14: an unreachable version endpoint no longer throws away a perfectly good
                // previously-downloaded copy - use the local file if there is one.
                if (!Files.exists(dir.resolve("rooms-modern.json"))) {
                    throw e;
                }
                LOGGER.warn("[RoomDatabase] Version check failed ({}) - using cached local room database (version={})",
                        e.toString(), localHash);
                remoteHash = localHash;
                cachedFallback = true;
            }

            if (!cachedFallback && (!remoteHash.equals(localHash) || !Files.exists(dir.resolve("rooms-modern.json")))) {
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
            consecutiveFailures = 0;
            LOGGER.info("[RoomDatabase] Loaded {} rooms ({} core hashes) on attempt #{}. version local={} remote={}",
                    entries.length, map.size(), loadAttempts, localHash, remoteHash);
        } catch (Exception e) {
            int failures = ++consecutiveFailures;
            long backoff = Math.min(MAX_RETRY_BACKOFF_MS, BASE_RETRY_BACKOFF_MS << Math.min(failures - 1, 5));
            nextAttemptAtMs = System.currentTimeMillis() + backoff;
            if (failures == 1) {
                LOGGER.warn("[RoomDatabase] Failed to load room database (failure #1) - retrying in {}s. Room names/secrets/solvers unavailable until then.",
                        backoff / 1000, e);
            } else {
                LOGGER.warn("[RoomDatabase] Failed to load room database (failure #{}: {}) - retrying in {}s.",
                        failures, e.toString(), backoff / 1000);
            }
        } finally {
            loading.set(false);
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
     *  room's rotation (corner index * 90 degrees). This overload checks one grid tile's own 32x32
     *  footprint - NoammAddons' per-tile path (used by it for L-shaped rooms, and here as the fallback
     *  for any room whose full tile set isn't known yet).
     *  @return {@code [clayX, clayZ, rotationDegrees]}, or null if no corner marker was found (yet). */
    public static int[] findRotationAndCorner(Level level, int roomCenterX, int roomCenterZ, int roofHeight) {
        return findRotationAndCorner(level, roomCenterX, roomCenterZ, roomCenterX, roomCenterZ, roofHeight);
    }

    /** Multi-tile version of {@link #findRotationAndCorner(Level, int, int, int)} - NoammAddons'
     *  {@code UniqueRoom.findRotation} for non-L rooms: the marker sits at one of the 4 corners of the
     *  bounding box spanning every tile center of the room ({@code min - 15} / {@code max + 15}).
     *  Unloaded corners are skipped. @return {@code [clayX, clayZ, rotationDegrees]}, or null. */
    public static int[] findRotationAndCorner(Level level, int minCenterX, int minCenterZ, int maxCenterX,
                                              int maxCenterZ, int roofHeight) {
        int h = 15;
        int minX = minCenterX - h;
        int maxX = maxCenterX + h;
        int minZ = minCenterZ - h;
        int maxZ = maxCenterZ + h;
        int[][] corners = {
                {minX, minZ}, {maxX, minZ}, {maxX, maxZ}, {minX, maxZ}
        };
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int i = 0; i < 4; i++) {
            pos.set(corners[i][0], roofHeight, corners[i][1]);
            if (!ChunkCacheManager.isLoadedOrCached(level, pos)) {
                continue;
            }
            if (level.getBlockState(pos).is(Blocks.BLUE_TERRACOTTA)) {
                return new int[]{corners[i][0], corners[i][1], i * 90};
            }
        }
        return null;
    }

    /** @return how many grid tiles a room of this database {@code shape} ("1x1", "1x2", "1x3", "1x4",
     *  "2x2", "L") covers - NoammAddons' {@code RoomShape.tileCount}; 4 (the largest real room) when the
     *  shape is missing or unrecognised. */
    public static int shapeTileCount(String shape) {
        if (shape == null) {
            return 4;
        }
        return switch (shape) {
            case "1x1" -> 1;
            case "1x2" -> 2;
            case "1x3", "L" -> 3;
            default -> 4;
        };
    }

    /** Real relative-to-absolute secret coordinate transform, ported from NoammAddons' own
     *  {@code ScanUtils.getRealCoord}. */
    public static BlockPos toRealCoord(RoomEntry.Pos relative, int clayX, int clayZ, int rotationDegrees) {
        BlockPos rotated = rotate(relative.x, relative.y, relative.z, rotationDegrees);
        return rotated.offset(clayX, 0, clayZ);
    }

    /** Real absolute-to-relative secret coordinate transform - the exact inverse of {@link #toRealCoord},
     *  needed by puzzle solvers (e.g. {@code WeirdosSolver}) that read a real LIVE entity's own world
     *  position and need to reason about it in the room's own relative coordinate space (to then apply a
     *  further relative-space offset before converting back). */
    public static RoomEntry.Pos toRelativeCoord(BlockPos real, int clayX, int clayZ, int rotationDegrees) {
        BlockPos offset = real.offset(-clayX, 0, -clayZ);
        BlockPos unrotated = rotate(offset.getX(), offset.getY(), offset.getZ(), (360 - (rotationDegrees % 360)) % 360);
        RoomEntry.Pos result = new RoomEntry.Pos();
        result.x = unrotated.getX();
        result.y = unrotated.getY();
        result.z = unrotated.getZ();
        return result;
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
