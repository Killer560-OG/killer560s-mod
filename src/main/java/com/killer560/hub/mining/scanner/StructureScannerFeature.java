package com.killer560.hub.mining.scanner;

import com.killer560.hub.mining.MiningIsland;
import com.killer560.hub.mining.chmap.ChDiscovery;
import com.killer560.hub.mining.chmap.ChFind;
import com.killer560.hub.util.FeatureBits;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.WorldRenderUtils;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Structure Scanner (CHEAT BUILD ONLY, not automation): the "illegal map" half of killer560's Crystal Hollows map spec
 * (MINING-PLAN.md 1c) - it reads the loaded chunks around you for each structure's block column ({@link ChSignature})
 * and marks what it finds with a waypoint, without your having to walk in. One chunk per tick, each once per lobby, up
 * to 8 chunks out. It reads blocks the client already has and never clicks, moves or sends anything; it is cheat-only
 * because knowing a structure you have not seen is information the game does not give you.
 *
 * <p>With Show On Map, finds are added to the Crystal Hollows Map as SCANNED; {@code ChShare} never sends a scanned
 * find to other players.
 */
public final class StructureScannerFeature {

    private static final int RADIUS_CHUNKS = 8;
    private static final int MIN_Y = 30;
    private static final int MAX_Y = 180;

    private static final Set<Long> SCANNED = new HashSet<>();
    private static final Map<ChSignature, List<BlockPos>> FOUND = new EnumMap<>(ChSignature.class);
    /** First block of each column -> the signatures starting with it, so a position costs one block read. */
    private static final Map<Block, List<ChSignature>> BY_FIRST = new HashMap<>();

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
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(StructureScannerFeature::reset));
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(FeatureGuard.afterFeatures(
                FeatureBits.register("Structure Scanner render", () -> StructureScannerConfig.getInstance().isEnabled()),
                StructureScannerFeature::onWorldRender));
    }

    public static void reset() {
        SCANNED.clear();
        FOUND.clear();
    }

    private static void onTick(Minecraft client) {
        StructureScannerConfig cfg = StructureScannerConfig.getInstance();
        if (!cfg.isEnabled() || client.player == null || client.level == null || !MiningIsland.crystalHollows()) {
            return;
        }
        int pcx = client.player.blockPosition().getX() >> 4;
        int pcz = client.player.blockPosition().getZ() >> 4;
        // Nearest unscanned loaded chunk first (rings outward), one per tick.
        for (int r = 0; r <= RADIUS_CHUNKS; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue;
                    }
                    int cx = pcx + dx;
                    int cz = pcz + dz;
                    long key = (((long) cx) << 32) ^ (cz & 0xFFFFFFFFL);
                    if (SCANNED.contains(key)) {
                        continue;
                    }
                    ChunkAccess chunk = client.level.getChunk(cx, cz, ChunkStatus.FULL, false);
                    if (chunk == null) {
                        continue;
                    }
                    SCANNED.add(key);
                    scanChunk(cfg, chunk, cx, cz);
                    return;
                }
            }
        }
    }

    private static void scanChunk(StructureScannerConfig cfg, ChunkAccess chunk, int cx, int cz) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int wx = (cx << 4) + x;
                int wz = (cz << 4) + z;
                for (int y = MIN_Y; y <= MAX_Y; y++) {
                    pos.set(wx, y, wz);
                    List<ChSignature> candidates = BY_FIRST.get(chunk.getBlockState(pos).getBlock());
                    if (candidates == null) {
                        continue;
                    }
                    for (ChSignature s : candidates) {
                        if (s == ChSignature.FAIRY_GROTTO && !cfg.isGrottos()) {
                            continue;
                        }
                        if (!s.quarter.test.test(pos) || alreadyHave(s, wx, wz) || !matches(chunk, s, wx, y, wz)) {
                            continue;
                        }
                        BlockPos at = new BlockPos(wx + s.dx, y + s.dy, wz + s.dz);
                        FOUND.computeIfAbsent(s, k -> new ArrayList<>()).add(at);
                        if (cfg.isChatOnFind()) {
                            ModChat.send("Structure Scanner", ModChat.text("Found "), ModChat.value(s.displayName),
                                    ModChat.text(" at " + at.getX() + ", " + at.getY() + ", " + at.getZ()));
                        }
                        if (cfg.isShowOnMap() && s.mapStructure != null) {
                            ChDiscovery.record(s.mapStructure, ChFind.Source.SCANNED, at.getX(), at.getY(), at.getZ());
                        }
                    }
                }
            }
        }
    }

    private static boolean alreadyHave(ChSignature s, int x, int z) {
        List<BlockPos> have = FOUND.get(s);
        if (have == null || have.isEmpty()) {
            return false;
        }
        if (!s.multiple) {
            return true;
        }
        for (BlockPos p : have) {
            if (Math.abs((p.getX() >> 4) - (x >> 4)) <= 4 && Math.abs((p.getZ() >> 4) - (z >> 4)) <= 4) {
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
        if (!StructureScannerConfig.getInstance().isWaypoints() || FOUND.isEmpty() || !MiningIsland.crystalHollows()) {
            return;
        }
        List<AABB> boxes = new ArrayList<>();
        for (List<BlockPos> list : FOUND.values()) {
            for (BlockPos p : list) {
                boxes.add(new AABB(p.getX(), p.getY(), p.getZ(), p.getX() + 1, p.getY() + 1, p.getZ() + 1));
            }
        }
        int n = boxes.size();
        float[] rgba = new float[n * 4];
        for (int i = 0; i < n; i++) {
            rgba[i * 4] = 1f;
            rgba[i * 4 + 1] = 0.85f;
            rgba[i * 4 + 2] = 0.2f;
            rgba[i * 4 + 3] = 1f;
        }
        WorldRenderUtils.renderOutlineBoxes(context, boxes.toArray(new AABB[0]), rgba, n, 3f, true);
    }

    /** Where the scanner put {@code s} this lobby (the first one, for a structure there can be several of), or null. */
    public static BlockPos position(ChSignature s) {
        List<BlockPos> l = FOUND.get(s);
        return l == null || l.isEmpty() ? null : l.get(0);
    }

    /** Every structure found this lobby. */
    public static Set<ChSignature> found() {
        return FOUND.isEmpty() ? Set.of() : Set.copyOf(FOUND.keySet());
    }

    /** For the tab. */
    public static String status() {
        return SCANNED.size() + " chunks scanned; " + FOUND.size() + " kinds of structure found";
    }
}
