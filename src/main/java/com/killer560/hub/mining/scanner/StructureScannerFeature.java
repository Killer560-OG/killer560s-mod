package com.killer560.hub.mining.scanner;

import com.killer560.hub.islands.IslandRender;
import com.killer560.hub.mining.MiningIsland;
import com.killer560.hub.mining.chmap.ChDiscovery;
import com.killer560.hub.mining.chmap.ChFind;
import com.killer560.hub.util.FeatureBits;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.WorldRenderUtils;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Structure Scanner (CHEAT BUILD ONLY, not automation): the "illegal map" half of killer560's Crystal Hollows map spec
 * (MINING-PLAN.md 1c) - it reads every chunk of the Crystal Hollows the client loads for each structure's block column
 * ({@link ChSignature}) and marks what it finds with a named waypoint, without your having to walk in. Each chunk is
 * scanned once per lobby (killer560, 2026-11-04: "it should do a scan of every chunk once for the structures"), one per
 * tick, as it arrives ({@code CHUNK_LOAD}) plus the ones already loaded when it is switched on; only the chunk sections
 * whose palette holds a column's first block are read. It reads blocks the client already has and never clicks, moves or
 * sends anything; it is cheat-only because knowing a structure you have not seen is information the game does not give you.
 *
 * <p>With Show On Map, finds are added to the Crystal Hollows Map as SCANNED; {@code ChShare} never sends a scanned
 * find to other players.
 */
public final class StructureScannerFeature {

    private static final int MIN_Y = 30;
    private static final int MAX_Y = 180;
    /** Chunk keys waiting; a whole Hollows is 64 x 64 chunks, so this never drops a real one. */
    private static final int MAX_PENDING = 8192;
    private static final int COLOR = 0xFFD933;

    private static final Set<Long> SCANNED = new HashSet<>();
    private static final ArrayDeque<Long> PENDING = new ArrayDeque<>();
    private static final List<ChSignature> FOUND_KIND = new ArrayList<>();
    private static final List<BlockPos> FOUND_AT = new ArrayList<>();
    /** First block of each column -> the signatures starting with it, so a position costs one block read. */
    private static final Map<Block, List<ChSignature>> BY_FIRST = new HashMap<>();
    private static final Predicate<BlockState> MAY_START = st -> BY_FIRST.containsKey(st.getBlock());

    private static Level level;
    /** Whether the chunks already loaded when it started (or entered this world) are queued. */
    private static boolean seeded;
    private static AABB[] boxes = new AABB[0];
    private static float[] rgba = new float[0];
    private static String lastLabel;

    static {
        for (ChSignature s : ChSignature.values()) {
            BY_FIRST.computeIfAbsent(s.column.get(0), k -> new ArrayList<>()).add(s);
        }
    }

    private StructureScannerFeature() {
    }

    public static void register() {
        WorldRenderUtils.initThroughWalls();
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("StructureScannerFeature", StructureScannerFeature::onTick));
        ClientChunkEvents.CHUNK_LOAD.register((world, chunk) -> {
            if (StructureScannerConfig.getInstance().isEnabled() && world == level && PENDING.size() < MAX_PENDING) {
                PENDING.add(key(chunk.getPos().x(), chunk.getPos().z()));
            }
        });
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(FeatureGuard.afterFeatures(
                FeatureBits.register("Structure Scanner render", () -> StructureScannerConfig.getInstance().isEnabled()),
                StructureScannerFeature::onWorldRender));
    }

    private static void reset() {
        SCANNED.clear();
        PENDING.clear();
        FOUND_KIND.clear();
        FOUND_AT.clear();
        boxes = new AABB[0];
        rgba = new float[0];
        seeded = false;
        lastLabel = null;
    }

    private static long key(int cx, int cz) {
        return (((long) cx) << 32) ^ (cz & 0xFFFFFFFFL);
    }

    private static void onTick(Minecraft client) {
        StructureScannerConfig cfg = StructureScannerConfig.getInstance();
        if (client.level != level || !cfg.isEnabled()) {
            level = client.level;
            if (seeded || !FOUND_AT.isEmpty()) {
                reset(); // a new world, or switched off: the next lobby (or switching on) scans from scratch
            }
        }
        if (!cfg.isEnabled() || client.player == null || level == null || !MiningIsland.crystalHollows()) {
            return;
        }
        if (!seeded) {
            seeded = true;
            int pcx = client.player.blockPosition().getX() >> 4;
            int pcz = client.player.blockPosition().getZ() >> 4;
            int r = client.options.getEffectiveRenderDistance() + 2;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    PENDING.add(key(pcx + dx, pcz + dz));
                }
            }
        }
        // One chunk scanned per tick; keys already scanned or since unloaded are skipped (a reload queues it again).
        for (int i = 0; i < 256 && !PENDING.isEmpty(); i++) {
            long k = PENDING.poll();
            if (SCANNED.contains(k)) {
                continue;
            }
            int cx = (int) (k >> 32);
            int cz = (int) k;
            ChunkAccess chunk = level.getChunk(cx, cz, ChunkStatus.FULL, false);
            if (chunk == null) {
                continue;
            }
            SCANNED.add(k);
            scanChunk(cfg, chunk, cx, cz);
            return;
        }
    }

    private static void scanChunk(StructureScannerConfig cfg, ChunkAccess chunk, int cx, int cz) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        LevelChunkSection[] sections = chunk.getSections();
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            int baseY = chunk.getSectionYFromSectionIndex(i) << 4;
            if (baseY + 15 < MIN_Y || baseY > MAX_Y || section == null || section.hasOnlyAir()
                    || !section.maybeHas(MAY_START)) {
                continue;
            }
            for (int y = Math.max(MIN_Y, baseY); y <= Math.min(MAX_Y, baseY + 15); y++) {
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        List<ChSignature> candidates = BY_FIRST.get(section.getBlockState(x, y & 15, z).getBlock());
                        if (candidates != null) {
                            check(cfg, chunk, candidates, pos.set((cx << 4) + x, y, (cz << 4) + z));
                        }
                    }
                }
            }
        }
    }

    private static void check(StructureScannerConfig cfg, ChunkAccess chunk, List<ChSignature> candidates,
                              BlockPos.MutableBlockPos pos) {
        int wx = pos.getX();
        int y = pos.getY();
        int wz = pos.getZ();
        for (ChSignature s : candidates) {
            if (s == ChSignature.FAIRY_GROTTO && !cfg.isGrottos()) {
                continue;
            }
            if (!s.quarter.test.test(pos) || alreadyHave(s, wx, wz) || !matches(chunk, s, wx, y, wz)) {
                continue;
            }
            BlockPos at = new BlockPos(wx + s.dx, y + s.dy, wz + s.dz);
            add(s, at);
            if (cfg.isChatOnFind()) {
                ModChat.send("Structure Scanner", ModChat.text("Found "), ModChat.value(s.displayName),
                        ModChat.text(" at " + at.getX() + ", " + at.getY() + ", " + at.getZ()));
            }
            if (cfg.isShowOnMap()) {
                ChDiscovery.record(s.mapStructure, ChFind.Source.SCANNED, at.getX(), at.getY(), at.getZ());
            }
        }
    }

    /** Keeps the find and rebuilds the boxes the render draws, so a frame allocates nothing. */
    private static void add(ChSignature s, BlockPos at) {
        FOUND_KIND.add(s);
        FOUND_AT.add(at);
        int n = FOUND_AT.size();
        boxes = new AABB[n];
        rgba = new float[n * 4];
        for (int i = 0; i < n; i++) {
            BlockPos p = FOUND_AT.get(i);
            boxes[i] = new AABB(p.getX(), p.getY(), p.getZ(), p.getX() + 1, p.getY() + 1, p.getZ() + 1);
            rgba[i * 4] = 1f;
            rgba[i * 4 + 1] = 0.85f;
            rgba[i * 4 + 2] = 0.2f;
            rgba[i * 4 + 3] = 1f;
        }
    }

    private static boolean alreadyHave(ChSignature s, int x, int z) {
        for (int i = 0; i < FOUND_KIND.size(); i++) {
            if (FOUND_KIND.get(i) != s) {
                continue;
            }
            BlockPos p = FOUND_AT.get(i);
            if (!s.multiple || (Math.abs((p.getX() >> 4) - (x >> 4)) <= 4 && Math.abs((p.getZ() >> 4) - (z >> 4)) <= 4)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matches(ChunkAccess chunk, ChSignature s, int x, int y0, int z) {
        if (y0 + s.column.size() >= MAX_Y + 1) {
            return false;
        }
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int i = 1; i < s.column.size(); i++) {
            Block want = s.column.get(i);
            if (want == null) {
                continue;
            }
            p.set(x, y0 + i, z);
            if (chunk.getBlockState(p).getBlock() != want) {
                return false;
            }
        }
        return true;
    }

    private static void onWorldRender(LevelRenderContext context) {
        int n = boxes.length;
        if (!StructureScannerConfig.getInstance().isWaypoints() || n == 0 || !MiningIsland.crystalHollows()) {
            return;
        }
        WorldRenderUtils.renderOutlineBoxes(context, boxes, rgba, n, 3f, true);
        // The structure's name over each box, through walls like the box (killer560, 2026-11-04).
        for (int i = 0; i < n; i++) {
            BlockPos p = FOUND_AT.get(i);
            lastLabel = FOUND_KIND.get(i).displayName;
            IslandRender.label(context, p.getX() + 0.5, p.getY() + 1.6, p.getZ() + 0.5, lastLabel, COLOR, true);
        }
    }

    /** Where the scanner put {@code s} this lobby (the first one, for a structure there can be several of), or null. */
    public static BlockPos position(ChSignature s) {
        int i = FOUND_KIND.indexOf(s);
        return i < 0 ? null : FOUND_AT.get(i);
    }

    /** The name the last frame drew over a find, or null (testkit: the waypoint really carries its name). */
    public static String lastLabel() {
        return lastLabel;
    }
}
