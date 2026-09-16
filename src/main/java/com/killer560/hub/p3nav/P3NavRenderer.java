package com.killer560.hub.p3nav;

import com.killer560.hub.util.WorldRenderUtils;
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
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.Optional;

/**
 * Box drawing for the P3 navigation aids. Depth-tested boxes go straight through this mod's existing
 * {@link WorldRenderUtils}; through-walls boxes (Terminal ESP, cheat build only) need a pipeline with no
 * depth test, built the same way {@code mobesp/EspRenderer} builds its two - copies of vanilla's LINES /
 * DEBUG_FILLED snippets with {@code withDepthStencilState(Optional.empty())}, which is how NoammAddons'
 * 26.1.2 {@code NoammRenderPipelines} does it. The pipeline ids are p3nav-specific so they can't collide
 * with Dungeon ESP's own registrations.
 * <p>
 * (The vertex layout below is the same Odin-derived one {@code WorldRenderUtils}/{@code EspRenderer} use.
 * If the main session would rather have one shared renderer, {@code EspRenderer.outline}/{@code filled}
 * are identical apart from the fixed 0.35 fill alpha - see the report.)
 */
final class P3NavRenderer {

    private P3NavRenderer() {
    }

    /** Called from {@link P3NavFeature#register()} so the pipelines exist before the renderer precompiles. */
    static void init() {
        if (com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            RenderType unused = ThroughWalls.LINES;
        }
    }

    /** Holder idiom - built once, on {@link #init()}. */
    private static final class ThroughWalls {
        static final RenderType LINES = RenderType.create("killer560smod_p3nav_lines_through_walls",
                RenderSetup.builder(RenderPipelines.register(RenderPipeline.builder(RenderPipelines.LINES_SNIPPET)
                        .withLocation(Identifier.fromNamespaceAndPath("killer560smod", "pipeline/p3nav_lines_through_walls"))
                        .withCull(false)
                        .withDepthStencilState(Optional.<DepthStencilState>empty())
                        .build())).createRenderSetup());

        static final RenderType FILLED = RenderType.create("killer560smod_p3nav_filled_through_walls",
                RenderSetup.builder(RenderPipelines.register(RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
                        .withLocation(Identifier.fromNamespaceAndPath("killer560smod", "pipeline/p3nav_filled_through_walls"))
                        .withCull(false)
                        .withDepthStencilState(Optional.<DepthStencilState>empty())
                        .build())).sortOnUpload().createRenderSetup());
    }

    /** Fill is drawn at 35% of the colour's own alpha so whatever is inside the box stays readable. */
    private static final float FILL_ALPHA_SCALE = 0.35f;

    static void draw(LevelRenderContext context, AABB box, int argb, P3NavConfig.Style style, float lineWidth,
                     boolean throughWalls) {
        boolean fill = style != P3NavConfig.Style.OUTLINE;
        boolean outline = style != P3NavConfig.Style.FILLED;
        if (fill) {
            float[] c = rgba(argb, FILL_ALPHA_SCALE);
            if (throughWalls) {
                filledThroughWalls(context, box, c);
            } else {
                WorldRenderUtils.renderFilledBox(context, box, c[0], c[1], c[2], c[3]);
            }
        }
        if (outline) {
            float[] c = rgba(argb, 1.0f);
            if (throughWalls) {
                outlineThroughWalls(context, box, c, lineWidth);
            } else {
                WorldRenderUtils.renderOutlineBox(context, box, c[0], c[1], c[2], c[3], lineWidth);
            }
        }
    }

    private static void outlineThroughWalls(LevelRenderContext context, AABB box, float[] c, float lineWidth) {
        MultiBufferSource.BufferSource buffers = context.bufferSource();
        if (buffers == null) {
            return;
        }
        PoseStack poseStack = context.poseStack();
        Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().position();
        poseStack.pushPose();
        poseStack.translate(-cam.x, -cam.y, -cam.z);
        lineBox(poseStack.last(), buffers.getBuffer(ThroughWalls.LINES), box, c[0], c[1], c[2], c[3], lineWidth);
        poseStack.popPose();
    }

    private static void filledThroughWalls(LevelRenderContext context, AABB box, float[] c) {
        MultiBufferSource.BufferSource buffers = context.bufferSource();
        if (buffers == null) {
            return;
        }
        PoseStack poseStack = context.poseStack();
        Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().position();
        poseStack.pushPose();
        poseStack.translate(-cam.x, -cam.y, -cam.z);
        filledBox(poseStack.last().pose(), buffers.getBuffer(ThroughWalls.FILLED), box, c[0], c[1], c[2], c[3]);
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
