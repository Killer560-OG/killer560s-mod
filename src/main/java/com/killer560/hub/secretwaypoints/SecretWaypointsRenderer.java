package com.killer560.hub.secretwaypoints;

import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.List;
import java.util.Optional;

/**
 * Box drawing for Secret Waypoints. killer560 (change 62): "draw them through walls" - a waypoint for a secret
 * you have not found yet is by definition behind something, so the depth-tested boxes
 * {@code WorldRenderUtils} draws were invisible exactly when they mattered.
 * <p>
 * The no-depth pipelines are built the same way {@code mobesp/EspRenderer} and {@code p3nav/P3NavRenderer}
 * build theirs - copies of vanilla's LINES / DEBUG_FILLED snippets with
 * {@code withDepthStencilState(Optional.empty())}, which is how NoammAddons' 26.1.2
 * {@code NoammRenderPipelines} does it. The pipeline ids are secretwaypoints-specific so they cannot collide
 * with Dungeon ESP's or P3 Nav's registrations. Vertex layout is the same Odin-derived one the rest of the
 * repo's box renderers use.
 * <p>
 * Everything is drawn in one camera-relative {@code pushPose}/{@code popPose} for the whole batch rather than
 * one per box (2026-09-20 FPS pass): with ~150 waypoints on screen that was 300 matrix pushes and 300
 * {@code getMainCamera()} lookups per frame.
 */
final class SecretWaypointsRenderer {

    private SecretWaypointsRenderer() {
    }

    /** Called from {@link SecretWaypointsFeature#register()} so the pipelines exist before the renderer
     *  precompiles its pipeline list. */
    static void init() {
        RenderType unused = ThroughWalls.LINES;
    }

    /** Holder idiom - built once, on {@link #init()}. */
    private static final class ThroughWalls {
        static final RenderType LINES = RenderType.create("killer560smod_secretwaypoints_lines_through_walls",
                RenderSetup.builder(RenderPipelines.register(RenderPipeline.builder(RenderPipelines.LINES_SNIPPET)
                        .withLocation(Identifier.fromNamespaceAndPath("killer560smod",
                                "pipeline/secretwaypoints_lines_through_walls"))
                        .withCull(false)
                        .withDepthStencilState(Optional.<DepthStencilState>empty())
                        .build())).createRenderSetup());

        static final RenderType FILLED = RenderType.create("killer560smod_secretwaypoints_filled_through_walls",
                RenderSetup.builder(RenderPipelines.register(RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
                        .withLocation(Identifier.fromNamespaceAndPath("killer560smod",
                                "pipeline/secretwaypoints_filled_through_walls"))
                        .withCull(false)
                        .withDepthStencilState(Optional.<DepthStencilState>empty())
                        .build())).sortOnUpload().createRenderSetup());
    }

    /**
     * Draws every waypoint in {@code waypoints} whose centre is within {@code maxDistance} blocks of the camera.
     *
     * @return how many boxes were actually drawn (the rest were distance-culled).
     */
    static int draw(LevelRenderContext context, List<SecretWaypointsFeature.Waypoint> waypoints,
                    SecretWaypointsConfig.Style style, boolean throughWalls, double maxDistance) {
        MultiBufferSource.BufferSource buffers = context.bufferSource();
        if (buffers == null || waypoints.isEmpty()) {
            return 0;
        }
        boolean fill = style != SecretWaypointsConfig.Style.OUTLINE;
        boolean outline = style != SecretWaypointsConfig.Style.FILL;
        float fillAlphaScale = style == SecretWaypointsConfig.Style.FILL_OUTLINE ? 0.5f : 1.0f;

        Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().position();
        double maxSq = maxDistance * maxDistance;

        PoseStack poseStack = context.poseStack();
        poseStack.pushPose();
        poseStack.translate(-cam.x, -cam.y, -cam.z);
        PoseStack.Pose pose = poseStack.last();

        int drawn = 0;
        VertexConsumer fillBuffer = null;
        VertexConsumer lineBuffer = null;
        for (int i = 0; i < waypoints.size(); i++) {
            SecretWaypointsFeature.Waypoint wp = waypoints.get(i);
            double dx = wp.centerX() - cam.x;
            double dy = wp.centerY() - cam.y;
            double dz = wp.centerZ() - cam.z;
            if (dx * dx + dy * dy + dz * dz > maxSq) {
                continue;
            }
            drawn++;
            if (fill) {
                if (fillBuffer == null) {
                    fillBuffer = buffers.getBuffer(throughWalls ? ThroughWalls.FILLED : RenderTypes.debugFilledBox());
                }
                filledBox(pose.pose(), fillBuffer, wp.box(), wp.r(), wp.g(), wp.b(), wp.a() * fillAlphaScale);
            }
            if (outline) {
                if (lineBuffer == null) {
                    lineBuffer = buffers.getBuffer(throughWalls ? ThroughWalls.LINES : RenderTypes.LINES_TRANSLUCENT);
                }
                lineBox(pose, lineBuffer, wp.box(), wp.r(), wp.g(), wp.b(), 1f, 2f);
            }
        }

        poseStack.popPose();
        return drawn;
    }

    private static final int[] EDGES = {
            0, 1, 1, 5, 5, 4, 4, 0,
            3, 2, 2, 6, 6, 7, 7, 3,
            0, 3, 1, 2, 5, 6, 4, 7
    };

    private static void lineBox(PoseStack.Pose pose, VertexConsumer buffer, AABB b, float r, float g, float bl,
                                float a, float width) {
        float x0 = (float) b.minX, y0 = (float) b.minY, z0 = (float) b.minZ;
        float x1 = (float) b.maxX, y1 = (float) b.maxY, z1 = (float) b.maxZ;
        float[] corners = {
                x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0,
                x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1
        };
        for (int i = 0; i < EDGES.length; i += 2) {
            int i0 = EDGES[i] * 3;
            int i1 = EDGES[i + 1] * 3;
            float sx = corners[i0], sy = corners[i0 + 1], sz = corners[i0 + 2];
            float ex = corners[i1], ey = corners[i1 + 1], ez = corners[i1 + 2];
            float dx = ex - sx, dy = ey - sy, dz = ez - sz;
            buffer.addVertex(pose, sx, sy, sz).setColor(r, g, bl, a).setNormal(pose, dx, dy, dz).setLineWidth(width);
            buffer.addVertex(pose, ex, ey, ez).setColor(r, g, bl, a).setNormal(pose, dx, dy, dz).setLineWidth(width);
        }
    }

    private static void filledBox(Matrix4f m, VertexConsumer buf, AABB box, float r, float g, float b, float a) {
        float x0 = (float) box.minX, y0 = (float) box.minY, z0 = (float) box.minZ;
        float x1 = (float) box.maxX, y1 = (float) box.maxY, z1 = (float) box.maxZ;
        float[][] quads = {
                {x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0},
                {x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1},
                {x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0},
                {x1, y0, z1, x1, y1, z1, x0, y1, z1, x0, y0, z1},
                {x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1},
                {x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0}
        };
        for (float[] q : quads) {
            for (int i = 0; i < 12; i += 3) {
                buf.addVertex(m, q[i], q[i + 1], q[i + 2]).setColor(r, g, b, a);
            }
        }
    }
}
