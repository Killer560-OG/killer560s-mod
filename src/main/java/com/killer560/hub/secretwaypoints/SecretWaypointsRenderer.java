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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * {@code getMainCamera()} lookups per frame. Unlike those two renderers, which fetch a fresh
 * {@code VertexConsumer} for every single box, this one fetches one per render type and then fills it in a
 * single uninterrupted pass - see the crash note in {@link #draw} for why that distinction matters.
 */
final class SecretWaypointsRenderer {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-secretwaypoints");

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

        // Distance cull once, into a reusable scratch array - the two passes below must walk exactly the
        // same set, and re-running the test per pass would give back the work the batching buys.
        if (visible.length < waypoints.size()) {
            visible = new int[waypoints.size()];
        }
        int[] vis = visible;
        int drawn = 0;
        for (int i = 0; i < waypoints.size(); i++) {
            SecretWaypointsFeature.Waypoint wp = waypoints.get(i);
            double dx = wp.centerX() - cam.x;
            double dy = wp.centerY() - cam.y;
            double dz = wp.centerZ() - cam.z;
            if (dx * dx + dy * dy + dz * dz <= maxSq) {
                vis[drawn++] = i;
            }
        }
        if (drawn == 0) {
            return 0;
        }

        PoseStack poseStack = context.poseStack();
        poseStack.pushPose();
        try {
            poseStack.translate(-cam.x, -cam.y, -cam.z);
            PoseStack.Pose pose = poseStack.last();
            // One getBuffer + one full pass PER RENDER TYPE, never interleaved per box. Crash fixed
            // 2026-09-20 (killer560's log, "java.lang.IllegalStateException: Not building!"): neither of
            // these two types is one of the level BufferSource's fixed buffers (javap-confirmed: only the
            // glint/waterMask types are), so both draw out of its single SHARED buffer, and
            // MultiBufferSource$BufferSource#getBuffer ends - i.e. build()s - whatever shared type was
            // started before handing out a different one. The FPS pass had hoisted both getBuffer calls
            // out of the loop but kept the loop alternating fill/outline per box, so box 1's outline
            // ended the fill builder and box 2's fill then wrote into an already-built BufferBuilder.
            // Two flat passes keep the hoist (2 draw calls for the whole batch instead of 2 per box)
            // while making a type switch mid-pass impossible.
            if (fill) {
                VertexConsumer fillBuffer =
                        buffers.getBuffer(throughWalls ? ThroughWalls.FILLED : RenderTypes.debugFilledBox());
                for (int i = 0; i < drawn; i++) {
                    SecretWaypointsFeature.Waypoint wp = waypoints.get(vis[i]);
                    filledBox(pose.pose(), fillBuffer, wp.box(), wp.r(), wp.g(), wp.b(), wp.a() * fillAlphaScale);
                }
            }
            if (outline) {
                VertexConsumer lineBuffer =
                        buffers.getBuffer(throughWalls ? ThroughWalls.LINES : RenderTypes.LINES_TRANSLUCENT);
                for (int i = 0; i < drawn; i++) {
                    SecretWaypointsFeature.Waypoint wp = waypoints.get(vis[i]);
                    lineBox(pose, lineBuffer, wp.box(), wp.r(), wp.g(), wp.b(), 1f, 2f);
                }
            }
        } catch (Throwable t) {
            // Never let a draw failure escape into LevelRenderer: the frame graph pass that calls this
            // event also owns the bufferSource.endBatch() that closes every builder started above, so an
            // exception thrown through it leaves a half-filled builder alive into the NEXT frame - which
            // is how one bad frame used to become a crash instead of a dropped frame of waypoints.
            if (!renderFailureLogged) {
                renderFailureLogged = true;
                LOGGER.error("[SecretWaypoints] Box rendering failed - waypoints will not be drawn this frame", t);
            }
            drawn = 0;
        } finally {
            poseStack.popPose();
        }
        return drawn;
    }

    /** Indices of the waypoints that passed this frame's distance cull. Render thread only; reused
     *  rather than allocated per frame (see the FPS note in the class doc). */
    private static int[] visible = new int[256];

    /** One report per session - a broken frame repeats at the frame rate, and the stack is identical. */
    private static boolean renderFailureLogged = false;

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
