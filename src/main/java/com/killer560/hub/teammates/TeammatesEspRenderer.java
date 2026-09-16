package com.killer560.hub.teammates;

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

import java.util.Optional;

/**
 * Teammate Highlight box drawing - a copy of {@code thorn.ThornEspRenderer}, which is itself a copy of
 * {@code mobesp.EspRenderer} (both package-private, in packages that belong to other features), with its own
 * pipeline ids. If EspRenderer is ever made public, delete this and call it instead.
 * <p>
 * Original doc: Dungeon ESP box drawing. Depth-tested boxes use vanilla's own {@code RenderTypes.LINES_TRANSLUCENT} /
 * {@code debugFilledBox()} (same as {@code WorldRenderUtils}); through-walls boxes use copies of those two pipelines
 * with the depth/stencil state removed - exactly how NoammAddons' 26.1.2 {@code NoammRenderPipelines}
 * ({@code lines_through_walls} / {@code filled_through_walls}) does it ({@code withDepthStencilState(Optional.empty())},
 * signature javap-verified on the 26.1.2 jar). Vertex layout is the same Odin-derived one {@code WorldRenderUtils} uses.
 */
final class TeammatesEspRenderer {

    private TeammatesEspRenderer() {
    }

    /** Called from client init (like Noamm registering its pipelines at init) so the two pipelines are in
     *  {@code RenderPipelines}' static list before the renderer precompiles it. */
    static void init() {
        RenderType unused = ThroughWalls.LINES;
    }

    /** Holder idiom - built once, on {@link #init()}. */
    private static final class ThroughWalls {
        static final RenderType LINES = RenderType.create("killer560smod_teammates_esp_lines_through_walls",
                RenderSetup.builder(RenderPipelines.register(RenderPipeline.builder(RenderPipelines.LINES_SNIPPET)
                        .withLocation(Identifier.fromNamespaceAndPath("killer560smod", "pipeline/teammates_esp_lines_through_walls"))
                        .withCull(false)
                        .withDepthStencilState(Optional.<DepthStencilState>empty())
                        .build())).createRenderSetup());

        static final RenderType FILLED = RenderType.create("killer560smod_teammates_esp_filled_through_walls",
                RenderSetup.builder(RenderPipelines.register(RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
                        .withLocation(Identifier.fromNamespaceAndPath("killer560smod", "pipeline/teammates_esp_filled_through_walls"))
                        .withCull(false)
                        .withDepthStencilState(Optional.<DepthStencilState>empty())
                        .build())).sortOnUpload().createRenderSetup());
    }

    static void outline(LevelRenderContext context, AABB box, int argb, float lineWidth, boolean throughWalls) {
        MultiBufferSource.BufferSource buffers = context.bufferSource();
        if (buffers == null) {
            return;
        }
        float[] c = rgba(argb, 1.0f);
        PoseStack poseStack = context.poseStack();
        Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().position();
        poseStack.pushPose();
        poseStack.translate(-cam.x, -cam.y, -cam.z);
        VertexConsumer buffer = buffers.getBuffer(throughWalls ? ThroughWalls.LINES : RenderTypes.LINES_TRANSLUCENT);
        lineBox(poseStack.last(), buffer, box, c[0], c[1], c[2], c[3], lineWidth);
        poseStack.popPose();
    }

    /** Fill is drawn at 35% of the colour's own alpha so the mob stays visible inside it. */
    static void filled(LevelRenderContext context, AABB box, int argb, boolean throughWalls) {
        MultiBufferSource.BufferSource buffers = context.bufferSource();
        if (buffers == null) {
            return;
        }
        float[] c = rgba(argb, 0.35f);
        PoseStack poseStack = context.poseStack();
        Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().position();
        poseStack.pushPose();
        poseStack.translate(-cam.x, -cam.y, -cam.z);
        VertexConsumer buffer = buffers.getBuffer(throughWalls ? ThroughWalls.FILLED : RenderTypes.debugFilledBox());
        filledBox(poseStack.last().pose(), buffer, box, c[0], c[1], c[2], c[3]);
        poseStack.popPose();
    }

    private static float[] rgba(int argb, float alphaScale) {
        float a = ((argb >>> 24) & 0xFF) / 255f;
        if (a <= 0f) {
            a = 1f;
        }
        return new float[]{((argb >> 16) & 0xFF) / 255f, ((argb >> 8) & 0xFF) / 255f, (argb & 0xFF) / 255f, a * alphaScale};
    }

    private static final int[] EDGES = {
            0, 1, 1, 5, 5, 4, 4, 0,
            3, 2, 2, 6, 6, 7, 7, 3,
            0, 3, 1, 2, 5, 6, 4, 7
    };

    private static void lineBox(PoseStack.Pose pose, VertexConsumer buffer, AABB b, float r, float g, float bl, float a,
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
