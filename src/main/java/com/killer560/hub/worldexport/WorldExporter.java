package com.killer560.hub.worldexport;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.killer560.hub.chunkcache.ChunkCacheManager;
import com.killer560.hub.chunkcache.ChunkCacheStore;
import com.killer560.hub.compat.McChunkExport;
import com.killer560.hub.util.ModLog;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.slf4j.Logger;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * World export (killer560, 2026-11-03: "a full world download of the hypixel skyblock hub for stuff like fairy soul
 * pathfinding testing"). Writes every chunk this client holds for the current world - the Chunk Cache's store plus the
 * chunks vanilla still has live - into a vanilla Anvil world folder the testkit's dedicated server (or any vanilla
 * server of the same Minecraft version) can open. PRIVATE use only: Hypixel's builds are their property, so the output
 * is never bundled, published or uploaded; the command exists in cheat dev builds only (see {@link WorldExportCommand}).
 * <p>
 * Purely local: it reads chunk objects already in memory and writes files. It sends no packet and asks the server for
 * nothing, so Hypixel cannot see an export happen.
 */
public final class WorldExporter {

    private static final Logger LOGGER = ModLog.get("killer560smod-worldexport");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    /** How far around the player (in chunks) live chunks are looked for in addition to the cache. */
    private static final int LIVE_RADIUS = 48;

    private WorldExporter() {
    }

    /** Where exports go: system property, then env var, then the game folder. */
    public static Path baseDir() {
        String prop = System.getProperty("killer560.worldexport.dir", "");
        if (prop.isBlank()) {
            prop = System.getenv().getOrDefault("KILLER560_WORLD_CAPTURES", "");
        }
        if (!prop.isBlank()) {
            return Path.of(prop);
        }
        return FabricLoader.getInstance().getGameDir().resolve("killer560-world-captures");
    }

    /** Every chunk the client holds for this level: the cache's store plus vanilla's live ring. */
    public static Map<Long, LevelChunk> collect(ClientLevel level) {
        Map<Long, LevelChunk> out = new LinkedHashMap<>();
        ChunkCacheStore store = ChunkCacheManager.storeOf(level);
        if (store != null) {
            store.snapshotInto(out);
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            int pcx = mc.player.blockPosition().getX() >> 4;
            int pcz = mc.player.blockPosition().getZ() >> 4;
            for (int dx = -LIVE_RADIUS; dx <= LIVE_RADIUS; dx++) {
                for (int dz = -LIVE_RADIUS; dz <= LIVE_RADIUS; dz++) {
                    int cx = pcx + dx;
                    int cz = pcz + dz;
                    if (!level.getChunkSource().hasChunk(cx, cz)) {
                        continue;
                    }
                    LevelChunk chunk = ChunkCacheManager.vanillaGetChunk(level, cx, cz);
                    if (chunk != null && !(chunk instanceof EmptyLevelChunk)) {
                        out.put(ChunkPos.pack(cx, cz), chunk);
                    }
                }
            }
        }
        out.values().removeIf(c -> c instanceof EmptyLevelChunk);
        return out;
    }

    /** What an export wrote. */
    public record Result(Path folder, int chunks, int blockEntities, int regions, int minChunkX, int minChunkZ,
                         int maxChunkX, int maxChunkZ, int entities) {
    }

    public static Result export(ClientLevel level, String name) throws IOException {
        Minecraft mc = Minecraft.getInstance();
        Map<Long, LevelChunk> chunks = collect(level);
        int outsideArea = chunks.size();
        chunks.keySet().removeIf(k -> !CaptureGuide.inArea(ChunkPos.getX(k), ChunkPos.getZ(k)));
        outsideArea -= chunks.size();
        if (chunks.isEmpty()) {
            throw new IOException("no chunks are loaded or cached");
        }
        Path folder = baseDir().resolve(name);
        Path regionDir = folder.resolve("dimensions").resolve("minecraft").resolve("overworld").resolve("region");
        if (Files.exists(regionDir)) {
            try (var stream = Files.list(regionDir)) {
                for (Path p : (Iterable<Path>) stream::iterator) {
                    Files.delete(p);
                }
            }
        }
        Files.createDirectories(regionDir);

        // region -> chunks, so each .mca is opened once
        Map<Long, List<LevelChunk>> byRegion = new TreeMap<>();
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (Map.Entry<Long, LevelChunk> e : chunks.entrySet()) {
            int cx = ChunkPos.getX(e.getKey());
            int cz = ChunkPos.getZ(e.getKey());
            minX = Math.min(minX, cx);
            minZ = Math.min(minZ, cz);
            maxX = Math.max(maxX, cx);
            maxZ = Math.max(maxZ, cz);
            byRegion.computeIfAbsent(ChunkPos.pack(cx >> 5, cz >> 5), k -> new ArrayList<>()).add(e.getValue());
        }

        var registries = level.registryAccess();
        RegionStorageInfo info = new RegionStorageInfo(name, Level.OVERWORLD, "chunk");
        int blockEntityCount = 0;
        for (Map.Entry<Long, List<LevelChunk>> region : byRegion.entrySet()) {
            int rx = ChunkPos.getX(region.getKey());
            int rz = ChunkPos.getZ(region.getKey());
            Path file = regionDir.resolve("r." + rx + "." + rz + ".mca");
            try (RegionFile rf = new RegionFile(info, file, regionDir, false)) {
                for (LevelChunk chunk : region.getValue()) {
                    List<CompoundTag> bes = new ArrayList<>();
                    for (BlockPos pos : new ArrayList<>(chunk.getBlockEntitiesPos())) {
                        CompoundTag tag = chunk.getBlockEntityNbtForSaving(pos, registries);
                        if (tag != null) {
                            bes.add(tag);
                        }
                    }
                    blockEntityCount += bes.size();
                    CompoundTag tag = McChunkExport.chunkTag(level, chunk, bes);
                    try (DataOutputStream out = rf.getChunkDataOutputStream(chunk.getPos())) {
                        NbtIo.write(tag, out);
                    }
                }
            }
        }

        int sx = mc.player == null ? 0 : mc.player.blockPosition().getX();
        int sy = mc.player == null ? 100 : mc.player.blockPosition().getY();
        int sz = mc.player == null ? 0 : mc.player.blockPosition().getZ();
        writeLevelDat(folder, name, sx, sy, sz);
        writeWorldGenSettings(folder);

        int entityCount = EntityRecorder.write(level, folder.resolve("entities.json"));
        JsonObject meta = new JsonObject();
        meta.addProperty("name", name);
        meta.addProperty("minecraft", net.minecraft.SharedConstants.getCurrentVersion().name());
        meta.addProperty("clientDimension", level.dimension().identifier().toString());
        meta.addProperty("minY", level.getMinY());
        meta.addProperty("height", level.getHeight());
        meta.addProperty("chunks", chunks.size());
        meta.addProperty("droppedOutsideArea", outsideArea);
        meta.addProperty("blockEntities", blockEntityCount);
        JsonArray box = new JsonArray();
        box.add(minX);
        box.add(minZ);
        box.add(maxX);
        box.add(maxZ);
        meta.add("chunkBox", box);
        JsonArray spawn = new JsonArray();
        spawn.add(sx);
        spawn.add(sy);
        spawn.add(sz);
        meta.add("spawn", spawn);
        meta.addProperty("note", "PRIVATE capture of a server's world. Never publish, upload or bundle it.");
        Files.writeString(folder.resolve("export-info.json"), GSON.toJson(meta), StandardCharsets.UTF_8);
        LOGGER.info("[WorldExport] wrote {} chunks, {} block entities, {} regions to {}", chunks.size(), blockEntityCount,
                byRegion.size(), folder);
        return new Result(folder, chunks.size(), blockEntityCount, byRegion.size(), minX, minZ, maxX, maxZ, entityCount);
    }

    private static void writeLevelDat(Path folder, String name, int x, int y, int z) throws IOException {
        int dataVersion = net.minecraft.SharedConstants.getCurrentVersion().dataVersion().version();
        CompoundTag data = new CompoundTag();
        CompoundTag diff = new CompoundTag();
        diff.putString("difficulty", "easy");
        diff.putBoolean("hardcore", false);
        diff.putBoolean("locked", false);
        data.put("difficulty_settings", diff);
        data.putLong("Time", 0L);
        data.putInt("GameType", 1);
        data.putInt("version", 19133);
        data.putLong("LastPlayed", System.currentTimeMillis());
        CompoundTag spawn = new CompoundTag();
        spawn.put("pos", new IntArrayTag(new int[]{x, y, z}));
        spawn.putFloat("pitch", 0f);
        spawn.putString("dimension", "minecraft:overworld");
        spawn.putFloat("yaw", 0f);
        data.put("spawn", spawn);
        CompoundTag version = new CompoundTag();
        version.putBoolean("Snapshot", false);
        version.putString("Series", "main");
        version.putInt("Id", dataVersion);
        version.putString("Name", net.minecraft.SharedConstants.getCurrentVersion().name());
        data.put("Version", version);
        data.putString("LevelName", name);
        data.putBoolean("initialized", true);
        data.putInt("DataVersion", dataVersion);
        data.putBoolean("allowCommands", true);
        CompoundTag packs = new CompoundTag();
        ListTag enabled = new ListTag();
        enabled.add(net.minecraft.nbt.StringTag.valueOf("vanilla"));
        packs.put("Enabled", enabled);
        packs.put("Disabled", new ListTag());
        data.put("DataPacks", packs);
        CompoundTag root = new CompoundTag();
        root.put("Data", data);
        NbtIo.writeCompressed(root, folder.resolve("level.dat"));
    }

    /** A flat generator with no layers over the void biome: anything not captured is empty air. */
    private static void writeWorldGenSettings(Path folder) throws IOException {
        int dataVersion = net.minecraft.SharedConstants.getCurrentVersion().dataVersion().version();
        CompoundTag flat = new CompoundTag();
        flat.putString("biome", "minecraft:the_void");
        flat.putBoolean("features", false);
        flat.putBoolean("lakes", false);
        flat.put("layers", new ListTag());
        flat.put("structure_overrides", new ListTag());
        CompoundTag gen = new CompoundTag();
        gen.putString("type", "minecraft:flat");
        gen.put("settings", flat);
        CompoundTag overworld = new CompoundTag();
        overworld.put("generator", gen);
        overworld.putString("type", "minecraft:overworld");
        CompoundTag dims = new CompoundTag();
        dims.put("minecraft:overworld", overworld);
        CompoundTag data = new CompoundTag();
        data.putBoolean("bonus_chest", false);
        data.putLong("seed", 0L);
        data.putBoolean("generate_structures", false);
        data.put("dimensions", dims);
        CompoundTag root = new CompoundTag();
        root.put("data", data);
        root.putInt("DataVersion", dataVersion);
        Path dir = folder.resolve("data").resolve("minecraft");
        Files.createDirectories(dir);
        NbtIo.writeCompressed(root, dir.resolve("world_gen_settings.dat"));
    }
}
