package com.killer560.hub.storagesearch;

import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * World box around the chest a search result lives in - killer560 (2026-09-21): "if the item is in a chest it will
 * esp the chest and highlight the item inside of it". The highlight-inside half is the slot outline
 * {@link StorageSearchFeature} arms; this is the "which chest, over there" half.
 * <p>
 * Through-walls pipelines are built exactly the way {@code secretwaypoints/SecretWaypointsRenderer} builds its own
 * (vanilla's LINES / DEBUG_FILLED snippets with the depth state dropped), with storagesearch-specific pipeline ids
 * so they cannot collide with that feature's or Dungeon ESP's registrations. The whole thing early-returns on an
 * empty target list, so with nothing marked it costs one field read per frame.
 */
public final class StorageSearchEsp {

    /** At most this many chests are marked at once - clicking a new result replaces the oldest. */
    private static final int MAX_TARGETS = 8;
    private static final int COLOR_RGB = 0xCC6600;

    private record Target(BlockPos pos, long expiresAt) {
    }

    private static final List<Target> TARGETS = new ArrayList<>();

    private StorageSearchEsp() {
    }

    static void register() {
        RenderType unused = ThroughWalls.LINES;
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(StorageSearchEsp::onWorldRender);
    }

    /** Marks a chest for {@code durationMs}. Re-marking the same chest just refreshes its timer. */
    public static void mark(BlockPos pos, long durationMs) {
        if (pos == null) {
            return;
        }
        long expiry = System.currentTimeMillis() + durationMs;
        TARGETS.removeIf(t -> t.pos().equals(pos));
        TARGETS.add(new Target(pos.immutable(), expiry));
        while (TARGETS.size() > MAX_TARGETS) {
            TARGETS.remove(0);
        }
    }

    public static void clear() {
        TARGETS.clear();
    }

    public static boolean hasTargets() {
        return !TARGETS.isEmpty();
    }

    private static void onWorldRender(LevelRenderContext context) {
        // Nothing marked is the normal case - this must cost effectively nothing then.
        if (TARGETS.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        TARGETS.removeIf(t -> t.expiresAt() <= now);
        if (TARGETS.isEmpty()) {
            return;
        }
        StorageSearchConfig cfg = StorageSearchConfig.getInstance();
        if (!cfg.isEnabled() || !cfg.isSearchChests() || !cfg.isChestEsp()) {
            TARGETS.clear();
            return;
        }
        MultiBufferSource.BufferSource buffers = context.bufferSource();
        if (buffers == null) {
            return;
        }
        boolean throughWalls = cfg.isEspThroughWalls();
        float r = ((COLOR_RGB >> 16) & 0xFF) / 255f;
        float g = ((COLOR_RGB >> 8) & 0xFF) / 255f;
        float b = (COLOR_RGB & 0xFF) / 255f;

        Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().position();
        PoseStack poseStack = context.poseStack();
        poseStack.pushPose();
        try {
            poseStack.translate(-cam.x, -cam.y, -cam.z);
            PoseStack.Pose pose = poseStack.last();
            // One getBuffer + one full pass PER RENDER TYPE, never interleaved per box - see the "Not building!"
            // crash note in SecretWaypointsRenderer for why alternating types inside the loop is not safe here.
            VertexConsumer fillBuffer =
                    buffers.getBuffer(throughWalls ? ThroughWalls.FILLED : RenderTypes.debugFilledBox());
            for (Target target : TARGETS) {
                filledBox(pose.pose(), fillBuffer, boxFor(target.pos()), r, g, b, 0.25f);
            }
            VertexConsumer lineBuffer =
                    buffers.getBuffer(throughWalls ? ThroughWalls.LINES : RenderTypes.LINES_TRANSLUCENT);
            for (Target target : TARGETS) {
                lineBox(pose, lineBuffer, boxFor(target.pos()), r, g, b, 1f, 2.5f);
            }
        } finally {
            poseStack.popPose();
        }
    }

    /** A chest model is inset from its block and only 14/16 tall - a full block box floats visibly above it. */
    private static AABB boxFor(BlockPos pos) {
        return new AABB(pos.getX() + 0.0625, pos.getY(), pos.getZ() + 0.0625,
                pos.getX() + 0.9375, pos.getY() + 0.875, pos.getZ() + 0.9375);
    }

    /** Holder idiom - built once, on {@link #register()}. */
    private static final class ThroughWalls {
        static final RenderType LINES = RenderType.create("killer560smod_storagesearch_lines_through_walls",
                RenderSetup.builder(RenderPipelines.register(RenderPipeline.builder(RenderPipelines.LINES_SNIPPET)
                        .withLocation(Identifier.fromNamespaceAndPath("killer560smod",
                                "pipeline/storagesearch_lines_through_walls"))
                        .withCull(false)
                        .withDepthStencilState(Optional.<DepthStencilState>empty())
                        .build())).createRenderSetup());

        static final RenderType FILLED = RenderType.create("killer560smod_storagesearch_filled_through_walls",
                RenderSetup.builder(RenderPipelines.register(RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
                        .withLocation(Identifier.fromNamespaceAndPath("killer560smod",
                                "pipeline/storagesearch_filled_through_walls"))
                        .withCull(false)
                        .withDepthStencilState(Optional.<DepthStencilState>empty())
                        .build())).sortOnUpload().createRenderSetup());
    }

    private static final int[] EDGES = {
            0, 1, 1, 5, 5, 4, 4, 0,
            3, 2, 2, 6, 6, 7, 7, 3,
            0, 3, 1, 2, 5, 6, 4, 7
    };

    private static void lineBox(PoseStack.Pose pose, VertexConsumer buffer, AABB aabb,
                                float r, float g, float b, float a, float thickness) {
        float x0 = (float) aabb.minX, y0 = (float) aabb.minY, z0 = (float) aabb.minZ;
        float x1 = (float) aabb.maxX, y1 = (float) aabb.maxY, z1 = (float) aabb.maxZ;
        float[] corners = {
                x0, y0, z0,
                x1, y0, z0,
                x1, y1, z0,
                x0, y1, z0,
                x0, y0, z1,
                x1, y0, z1,
                x1, y1, z1,
                x0, y1, z1
        };
        for (int i = 0; i < EDGES.length; i += 2) {
            int i0 = EDGES[i] * 3;
            int i1 = EDGES[i + 1] * 3;
            float sx = corners[i0], sy = corners[i0 + 1], sz = corners[i0 + 2];
            float ex = corners[i1], ey = corners[i1 + 1], ez = corners[i1 + 2];
            float dx = ex - sx, dy = ey - sy, dz = ez - sz;
            buffer.addVertex(pose, sx, sy, sz).setColor(r, g, b, a).setNormal(pose, dx, dy, dz).setLineWidth(thickness);
            buffer.addVertex(pose, ex, ey, ez).setColor(r, g, b, a).setNormal(pose, dx, dy, dz).setLineWidth(thickness);
        }
    }

    private static void filledBox(Matrix4f m, VertexConsumer buf, AABB box, float r, float g, float b, float a) {
        float minX = (float) box.minX, minY = (float) box.minY, minZ = (float) box.minZ;
        float maxX = (float) box.maxX, maxY = (float) box.maxY, maxZ = (float) box.maxZ;

        vertex(buf, m, minX, minY, minZ, r, g, b, a);
        vertex(buf, m, minX, minY, maxZ, r, g, b, a);
        vertex(buf, m, minX, maxY, maxZ, r, g, b, a);
        vertex(buf, m, minX, maxY, minZ, r, g, b, a);

        vertex(buf, m, maxX, minY, maxZ, r, g, b, a);
        vertex(buf, m, maxX, minY, minZ, r, g, b, a);
        vertex(buf, m, maxX, maxY, minZ, r, g, b, a);
        vertex(buf, m, maxX, maxY, maxZ, r, g, b, a);

        vertex(buf, m, minX, minY, minZ, r, g, b, a);
        vertex(buf, m, minX, maxY, minZ, r, g, b, a);
        vertex(buf, m, maxX, maxY, minZ, r, g, b, a);
        vertex(buf, m, maxX, minY, minZ, r, g, b, a);

        vertex(buf, m, maxX, minY, maxZ, r, g, b, a);
        vertex(buf, m, maxX, maxY, maxZ, r, g, b, a);
        vertex(buf, m, minX, maxY, maxZ, r, g, b, a);
        vertex(buf, m, minX, minY, maxZ, r, g, b, a);

        vertex(buf, m, minX, minY, minZ, r, g, b, a);
        vertex(buf, m, maxX, minY, minZ, r, g, b, a);
        vertex(buf, m, maxX, minY, maxZ, r, g, b, a);
        vertex(buf, m, minX, minY, maxZ, r, g, b, a);

        vertex(buf, m, minX, maxY, maxZ, r, g, b, a);
        vertex(buf, m, maxX, maxY, maxZ, r, g, b, a);
        vertex(buf, m, maxX, maxY, minZ, r, g, b, a);
        vertex(buf, m, minX, maxY, minZ, r, g, b, a);
    }

    private static void vertex(VertexConsumer buffer, Matrix4f matrix, float x, float y, float z,
                               float r, float g, float b, float a) {
        buffer.addVertex(matrix, x, y, z).setColor(r, g, b, a);
    }
}
