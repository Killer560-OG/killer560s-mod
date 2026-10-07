package com.killer560.hub.witherdoors;

import com.killer560.hub.util.WorldRenderUtils;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Optional;
import com.killer560.hub.compat.McRender;

/**
 * Box drawing for {@link WitherDoorsFeature}. Depth-tested path reuses the shared
 * {@link WorldRenderUtils#renderOutlineBox} - the same call {@code doorkeys.DoorKeysFeature} uses - since
 * this feature never draws more than a small handful of boxes at once (a legit run draws exactly one).
 * The no-depth "Through Walls" path (cheat build only) needs its own pipeline, copied the same way
 * {@code doorkeys.DoorKeysFeature.ThroughWalls} and {@code secretwaypoints.SecretWaypointsRenderer.ThroughWalls}
 * build theirs - a vanilla LINES snippet with its depth/stencil state stripped, under a
 * killer560smod-namespaced pipeline id so it can't collide with either of those. The Fill / Filled Outline styles
 * (2026-10-07) draw through {@link WorldRenderUtils#renderFilledBoxes}, whose no-depth FILLED type is the shared one.
 */
final class WitherDoorsRenderer {

    private WitherDoorsRenderer() {
    }

    /** Called from {@link WitherDoorsFeature#register()}, cheat build only, so the pipeline exists before
     *  the renderer precompiles its pipeline list - same reason every other ESP-style feature in this
     *  repo touches its holder at init instead of on first draw. */
    static void init() {
        RenderType unused = ThroughWalls.LINES;
    }

    private static final class ThroughWalls {
        static final RenderType LINES = RenderType.create("killer560smod_witherdoors_lines_through_walls",
                RenderSetup.builder(RenderPipelines.register(RenderPipeline.builder(RenderPipelines.LINES_SNIPPET)
                        .withLocation(Identifier.fromNamespaceAndPath("killer560smod", "pipeline/witherdoors_lines_through_walls"))
                        .withCull(false)
                        .withDepthStencilState(Optional.<DepthStencilState>empty())
                        .build())).createRenderSetup());
    }

    private static final int[] EDGES = {
            0, 1, 1, 5, 5, 4, 4, 0,
            3, 2, 2, 6, 6, 7, 7, 3,
            0, 3, 1, 2, 5, 6, 4, 7
    };

    /**
     * Fill first, then the outline - two flat passes, never interleaved per box (the same order and reason as
     * {@code BreakerAuraFeature.drawPickBoxes}). The fill is {@link WorldRenderUtils#renderFilledBoxes}: depth-tested
     * {@code debugFilledBox()} normally, its shared no-depth FILLED pipeline with Through Walls (registered at init by
     * {@link WitherDoorsFeature#register()}). Each box's fill alpha is the Fill Opacity setting.
     */
    static void draw(LevelRenderContext context, List<WitherDoorsFeature.DoorBox> boxes, boolean throughWalls,
                     WitherDoorsConfig.Style style, float fillAlpha) {
        if (boxes.isEmpty()) {
            return;
        }
        if (style.fills()) {
            // Fresh arrays every frame: on 26.2 the callback runs later in the frame (renderFilledBoxes' contract).
            int n = boxes.size();
            AABB[] fillBoxes = new AABB[n];
            float[] rgba = new float[n * 4];
            for (int i = 0; i < n; i++) {
                WitherDoorsFeature.DoorBox box = boxes.get(i);
                fillBoxes[i] = box.box();
                rgba[i * 4] = box.fr();
                rgba[i * 4 + 1] = box.fg();
                rgba[i * 4 + 2] = box.fb();
                rgba[i * 4 + 3] = fillAlpha;
            }
            WorldRenderUtils.renderFilledBoxes(context, fillBoxes, rgba, n, 1f, throughWalls);
        }
        if (!style.outlines()) {
            return;
        }
        if (!throughWalls) {
            for (WitherDoorsFeature.DoorBox box : boxes) {
                WorldRenderUtils.renderOutlineBox(context, box.box(), box.r(), box.g(), box.b(), 1f, 2f);
            }
            return;
        }
        // A COPY, made now: on 26.2 the callback runs later in the frame, after the tick may have cleared and
        // rebuilt the live list (WitherDoorsFeature.CACHED) this method was handed.
        final WitherDoorsFeature.DoorBox[] snap = boxes.toArray(new WitherDoorsFeature.DoorBox[0]);
        McRender.inCameraSpace(context, ThroughWalls.LINES, (pose, buffer) -> {
                for (WitherDoorsFeature.DoorBox box : snap) {
                    lineBox(pose, buffer, box.box(), box.r(), box.g(), box.b(), 2f);
                }
        });
    }

    private static void lineBox(PoseStack.Pose pose, VertexConsumer buffer, AABB b, float r, float g, float bl,
                                float width) {
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
            buffer.addVertex(pose, sx, sy, sz).setColor(r, g, bl, 1f).setNormal(pose, dx, dy, dz).setLineWidth(width);
            buffer.addVertex(pose, ex, ey, ez).setColor(r, g, bl, 1f).setNormal(pose, dx, dy, dz).setLineWidth(width);
        }
    }
}
