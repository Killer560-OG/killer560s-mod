package com.killer560.hub.worldexport;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.killer560.hub.util.FeatureBits;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.WorldRenderUtils;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/**
 * The capture area and the end-of-walk guide (killer560, 2026-11-04: "have it highlight the chunk you want me to scan and a
 * line towards it when it gets towards the end"; and his hub capture had surrounding islands in it).
 * <p>
 * The area is a chunk rectangle kept in {@code area.json} beside the captures, so it survives a restart (it is a working
 * state of this private tool, not a profile setting). {@code record on} sets one around the player when none exists. Exports
 * keep only chunks inside it. While recording, when the missing share of the area is {@link #GUIDE_PERCENT} or less (or at
 * most {@link #GUIDE_CHUNKS} chunks), the nearest missing chunk is outlined in the world and a line runs to it.
 */
public final class CaptureGuide {

    /** Radius in chunks of the area {@code record on} makes around the player when none is set. */
    public static final int DEFAULT_RADIUS = 28;
    /** The guide shows when this share (percent) of the area or less is missing... */
    public static final int GUIDE_PERCENT = 15;
    /** ...or when this many chunks or fewer are missing. */
    public static final int GUIDE_CHUNKS = 12;

    private static final Gson GSON = new Gson();
    /** minCx, minCz, maxCx, maxCz, or null. */
    private static int[] area;
    private static boolean loaded;
    private static boolean registered;
    /** The chunk now highlighted (cx, cz) or null; refreshed twice a second. */
    private static volatile int[] target;
    private static volatile int[] lastCounts = new int[]{0, 0};
    private static float[] outlineRgba = {1f, 0.55f, 0.1f, 1f};

    private CaptureGuide() {
    }

    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        WorldRenderUtils.initThroughWalls();
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("CaptureGuide", client -> {
            if (client.level == null || client.player == null || !EntityRecorder.isRecording()
                    || client.player.tickCount % 10 != 0) {
                if (client.level == null || !EntityRecorder.isRecording()) {
                    target = null;
                }
                return;
            }
            target = computeTarget(client.level);
        }));
        LevelRenderEvents.COLLECT_SUBMITS.register(FeatureGuard.collectFeatures(
                FeatureBits.register("World capture guide", EntityRecorder::isRecording), CaptureGuide::onWorldRender));
    }

    // ---- area ----

    /** The area as {minCx, minCz, maxCx, maxCz}, or null when none is set. */
    public static synchronized int[] area() {
        load();
        return area == null ? null : area.clone();
    }

    public static synchronized void setArea(int minCx, int minCz, int maxCx, int maxCz) {
        load();
        area = new int[]{Math.min(minCx, maxCx), Math.min(minCz, maxCz), Math.max(minCx, maxCx), Math.max(minCz, maxCz)};
        save();
    }

    public static void setAreaAround(int blockX, int blockZ, int chunkRadius) {
        int cx = blockX >> 4;
        int cz = blockZ >> 4;
        setArea(cx - chunkRadius, cz - chunkRadius, cx + chunkRadius, cz + chunkRadius);
    }

    public static synchronized void clearArea() {
        load();
        area = null;
        save();
    }

    /** Block box {x1, z1, x2, z2} of the area, or null. */
    public static int[] areaBlocks() {
        int[] a = area();
        return a == null ? null : new int[]{a[0] * 16, a[1] * 16, a[2] * 16 + 15, a[3] * 16 + 15};
    }

    public static boolean inArea(int cx, int cz) {
        int[] a = area();
        return a == null || (cx >= a[0] && cx <= a[2] && cz >= a[1] && cz <= a[3]);
    }

    private static Path file() {
        return WorldExporter.baseDir().resolve("area.json");
    }

    private static void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        try {
            Path f = file();
            if (Files.isRegularFile(f)) {
                JsonObject o = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), JsonObject.class);
                if (o != null && o.has("minCx")) {
                    area = new int[]{o.get("minCx").getAsInt(), o.get("minCz").getAsInt(), o.get("maxCx").getAsInt(),
                            o.get("maxCz").getAsInt()};
                }
            }
        } catch (Exception e) {
            area = null;
        }
    }

    private static void save() {
        try {
            Path f = file();
            Files.createDirectories(f.getParent());
            if (area == null) {
                Files.deleteIfExists(f);
                return;
            }
            JsonObject o = new JsonObject();
            o.addProperty("minCx", area[0]);
            o.addProperty("minCz", area[1]);
            o.addProperty("maxCx", area[2]);
            o.addProperty("maxCz", area[3]);
            Files.writeString(f, GSON.toJson(o), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // a tool setting; a failed save leaves the in-memory area
        }
    }

    /** Test hook: forget the in-memory copy so the next read goes to the file. */
    public static synchronized void forgetMemory() {
        loaded = false;
        area = null;
    }

    // ---- the guide ----

    /** True when {@code missing} of {@code total} chunks is few enough to show the guide. */
    public static boolean fewRemain(int missing, int total) {
        return missing > 0 && (missing <= GUIDE_CHUNKS || missing * 100L <= (long) GUIDE_PERCENT * total);
    }

    /** The chunk now highlighted, {cx, cz}, or null. */
    public static int[] target() {
        return target;
    }

    /** {missing, total} behind the last refresh. */
    public static int[] lastCounts() {
        return lastCounts;
    }

    /**
     * The nearest missing chunk of the area to the player when few remain, else null. Pure over the captured set, so the
     * testkit can call it.
     */
    public static int[] pickTarget(Set<Long> have, int[] a, int playerCx, int playerCz) {
        if (a == null) {
            return null;
        }
        int total = (a[2] - a[0] + 1) * (a[3] - a[1] + 1);
        int missing = 0;
        long best = Long.MAX_VALUE;
        int[] pick = null;
        for (int z = a[1]; z <= a[3]; z++) {
            for (int x = a[0]; x <= a[2]; x++) {
                if (have.contains(ChunkPos.pack(x, z))) {
                    continue;
                }
                missing++;
                long d = (long) (x - playerCx) * (x - playerCx) + (long) (z - playerCz) * (z - playerCz);
                if (d < best) {
                    best = d;
                    pick = new int[]{x, z};
                }
            }
        }
        lastCounts = new int[]{missing, total};
        return fewRemain(missing, total) ? pick : null;
    }

    /** Refreshes {@link #target()} from the live client now. */
    public static int[] computeTarget(ClientLevel level) {
        int[] a = area();
        Minecraft mc = Minecraft.getInstance();
        if (a == null || mc.player == null) {
            return null;
        }
        Set<Long> have = WorldExporter.collect(level).keySet();
        return pickTarget(have, a, mc.player.blockPosition().getX() >> 4, mc.player.blockPosition().getZ() >> 4);
    }

    private static void onWorldRender(LevelRenderContext context) {
        int[] t = target;
        Minecraft mc = Minecraft.getInstance();
        if (t == null || mc.player == null || mc.level == null) {
            return;
        }
        double y0 = mc.level.getMinY();
        double y1 = mc.level.getMinY() + mc.level.getHeight();
        AABB box = new AABB(t[0] * 16.0, y0, t[1] * 16.0, t[0] * 16.0 + 16.0, y1, t[1] * 16.0 + 16.0);
        WorldRenderUtils.renderOutlineBox(context, box, outlineRgba[0], outlineRgba[1], outlineRgba[2], outlineRgba[3], 4f);
        Vec3 from = WorldRenderUtils.tracerOrigin(context);
        Vec3 to = new Vec3(t[0] * 16.0 + 8.0, mc.player.getEyeY(), t[1] * 16.0 + 8.0);
        WorldRenderUtils.renderLine(context, from, to, outlineRgba[0], outlineRgba[1], outlineRgba[2], 1f, 3f, true);
    }
}
