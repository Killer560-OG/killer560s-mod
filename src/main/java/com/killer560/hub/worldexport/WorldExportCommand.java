package com.killer560.hub.worldexport;

import com.killer560.hub.BuildVariant;
import com.killer560.hub.util.ModChat;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * {@code /k560worldexport}: coverage readout, entity recording and the export itself. Cheat dev builds only
 * ({@code DEV_TOOLS && CHEAT_FEATURES_ENABLED}); a legit build or any release build never registers it, and javac folds
 * the registration away. Private use: see {@link WorldExporter}.
 */
public final class WorldExportCommand {

    private static final String FEATURE = "World Export";

    private WorldExportCommand() {
    }

    public static void register() {
        if (!(BuildVariant.DEV_TOOLS && BuildVariant.CHEAT_FEATURES_ENABLED)) return;
        EntityRecorder.register();
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommands.literal("k560worldexport")
                        .executes(ctx -> help())
                        .then(ClientCommands.literal("coverage")
                                .executes(ctx -> coverage(null))
                                .then(ClientCommands.argument("x1", IntegerArgumentType.integer())
                                        .then(ClientCommands.argument("z1", IntegerArgumentType.integer())
                                                .then(ClientCommands.argument("x2", IntegerArgumentType.integer())
                                                        .then(ClientCommands.argument("z2", IntegerArgumentType.integer())
                                                                .executes(ctx -> coverage(new int[]{
                                                                        IntegerArgumentType.getInteger(ctx, "x1"),
                                                                        IntegerArgumentType.getInteger(ctx, "z1"),
                                                                        IntegerArgumentType.getInteger(ctx, "x2"),
                                                                        IntegerArgumentType.getInteger(ctx, "z2")})))))))
                        .then(ClientCommands.literal("save")
                                .then(ClientCommands.argument("name", StringArgumentType.word())
                                        .executes(ctx -> save(StringArgumentType.getString(ctx, "name")))))
                        .then(ClientCommands.literal("record")
                                .then(ClientCommands.literal("on").executes(ctx -> record(true)))
                                .then(ClientCommands.literal("off").executes(ctx -> record(false)))
                                .then(ClientCommands.literal("clear").executes(ctx -> {
                                    EntityRecorder.clear();
                                    ModChat.send(FEATURE, ModChat.text("Recorded entities cleared."));
                                    return 1;
                                })))));
    }

    private static int help() {
        ModChat.send(FEATURE, ModChat.text("coverage [x1 z1 x2 z2] | record on|off|clear | save <name>. Files go to "),
                ModChat.value(WorldExporter.baseDir().toString()));
        return 1;
    }

    private static int record(boolean on) {
        EntityRecorder.setRecording(on);
        ModChat.send(FEATURE, ModChat.text("Entity recording " + (on ? "ON" : "OFF") + ", "),
                ModChat.value(String.valueOf(EntityRecorder.count())), ModChat.text(" recorded."));
        return 1;
    }

    private static int save(String name) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            ModChat.send(FEATURE, ModChat.bad("Not in a world."));
            return 0;
        }
        try {
            WorldExporter.Result r = WorldExporter.export(mc.level, name);
            ModChat.send(FEATURE, ModChat.good("Saved " + r.chunks() + " chunks"), ModChat.text(" ("
                    + r.blockEntities() + " block entities, " + r.entities() + " entities, " + r.regions()
                    + " regions) to "), ModChat.value(r.folder().toString()));
            return 1;
        } catch (Exception e) {
            ModChat.send(FEATURE, ModChat.bad("Export failed: " + e));
            return 0;
        }
    }

    /** The numbers a coverage readout is made of, for the command and the testkit. */
    public record Coverage(int captured, int live, int minCx, int minCz, int maxCx, int maxCz, int boxChunks,
                           int missing, List<int[]> nearestMissing, String map) {
    }

    /**
     * @param blockBox x1 z1 x2 z2 in blocks, or null for the bounding box of what is captured
     */
    public static Coverage measure(ClientLevel level, int[] blockBox) {
        Map<Long, LevelChunk> chunks = WorldExporter.collect(level);
        int live = 0;
        for (LevelChunk c : chunks.values()) {
            if (com.killer560.hub.chunkcache.ChunkCacheManager.isLive(c)) {
                live++;
            }
        }
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        if (blockBox != null) {
            minX = Math.min(blockBox[0], blockBox[2]) >> 4;
            maxX = Math.max(blockBox[0], blockBox[2]) >> 4;
            minZ = Math.min(blockBox[1], blockBox[3]) >> 4;
            maxZ = Math.max(blockBox[1], blockBox[3]) >> 4;
        } else {
            for (long k : chunks.keySet()) {
                minX = Math.min(minX, ChunkPos.getX(k));
                maxX = Math.max(maxX, ChunkPos.getX(k));
                minZ = Math.min(minZ, ChunkPos.getZ(k));
                maxZ = Math.max(maxZ, ChunkPos.getZ(k));
            }
        }
        if (minX > maxX) return new Coverage(0, 0, 0, 0, 0, 0, 0, 0, List.of(), "");
        Minecraft mc = Minecraft.getInstance();
        int px = mc.player == null ? 0 : mc.player.blockPosition().getX() >> 4;
        int pz = mc.player == null ? 0 : mc.player.blockPosition().getZ() >> 4;
        int boxChunks = (maxX - minX + 1) * (maxZ - minZ + 1);
        List<int[]> missingList = new ArrayList<>();
        StringBuilder map = new StringBuilder();
        for (int z = minZ; z <= maxZ; z++) {
            for (int x = minX; x <= maxX; x++) {
                boolean have = chunks.containsKey(ChunkPos.pack(x, z));
                if (!have) {
                    missingList.add(new int[]{x, z});
                }
                map.append(x == px && z == pz ? '@' : have ? '#' : '.');
            }
            map.append('\n');
        }
        int missing = missingList.size();
        missingList.sort(Comparator.comparingLong(m -> (long) (m[0] - px) * (m[0] - px) + (long) (m[1] - pz) * (m[1] - pz)));
        List<int[]> nearest = new ArrayList<>();
        for (int i = 0; i < Math.min(5, missingList.size()); i++) {
            int[] m = missingList.get(i);
            nearest.add(new int[]{m[0] * 16 + 8, m[1] * 16 + 8});
        }
        return new Coverage(chunks.size() - (blockBox == null ? 0 : outside(chunks, minX, minZ, maxX, maxZ)), live, minX, minZ,
                maxX, maxZ, boxChunks, missing, nearest, map.toString());
    }

    private static int outside(Map<Long, LevelChunk> chunks, int minX, int minZ, int maxX, int maxZ) {
        int n = 0;
        for (long k : chunks.keySet()) {
            int x = ChunkPos.getX(k);
            int z = ChunkPos.getZ(k);
            if (x < minX || x > maxX || z < minZ || z > maxZ) {
                n++;
            }
        }
        return n;
    }

    private static int coverage(int[] box) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            ModChat.send(FEATURE, ModChat.bad("Not in a world."));
            return 0;
        }
        Coverage c = measure(mc.level, box);
        if (c.boxChunks() == 0) {
            ModChat.send(FEATURE, ModChat.bad("Nothing captured yet."));
            return 0;
        }
        int pct = (int) Math.round(100.0 * (c.boxChunks() - c.missing()) / c.boxChunks());
        ModChat.send(FEATURE, ModChat.text("Captured "), ModChat.value(String.valueOf(c.captured())),
                ModChat.text(" chunks (" + c.live() + " live). Box blocks "
                        + (c.minCx() * 16) + "," + (c.minCz() * 16) + " to " + (c.maxCx() * 16 + 15) + "," + (c.maxCz() * 16 + 15)
                        + ": "), ModChat.value(pct + "% filled"), ModChat.text(", " + c.missing() + " of " + c.boxChunks()
                        + " chunks missing."));
        if (!c.nearestMissing().isEmpty()) {
            StringBuilder sb = new StringBuilder("Nearest gaps (block x,z): ");
            for (int[] m : c.nearestMissing()) {
                sb.append(m[0]).append(',').append(m[1]).append("  ");
            }
            ModChat.send(FEATURE, ModChat.text(sb.toString().trim()));
        }
        try {
            Path file = WorldExporter.baseDir().resolve("coverage-map.txt");
            Files.createDirectories(file.getParent());
            Files.writeString(file, "# = captured, . = missing, @ = you. One character per chunk, north at the top. Box chunks "
                    + c.minCx() + "," + c.minCz() + " to " + c.maxCx() + "," + c.maxCz() + "\n" + c.map());
            ModChat.send(FEATURE, ModChat.text("Map written to "), ModChat.value(file.toString()));
        } catch (Exception e) {
            ModChat.send(FEATURE, ModChat.bad("Could not write the map: " + e));
        }
        return 1;
    }
}
