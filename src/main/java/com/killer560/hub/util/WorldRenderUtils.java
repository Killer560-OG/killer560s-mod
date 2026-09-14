package com.killer560.hub.util;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * This mod's first world-space (3D) box renderer - killer560s-mod had none before (every prior HUD
 * element is 2D screen-space GuiGraphics). Needed for Simon Says' button highlights, and written
 * generically enough for any future feature that wants to draw a box in the world (secret waypoints,
 * a real dungeon minimap, etc).
 * <p>
 * The actual vertex layout (which corners, in which order, for {@code RenderTypes.debugFilledBox()}'s
 * QUADS format and a plain LINES wireframe) is ported directly from Odin's own
 * {@code PrimitiveRenderer.addChainedFilledBoxVertices}/{@code renderLineBox} - real, proven-compiling
 * code confirmed against this exact Minecraft version (Odin's {@code gradle.properties} pins
 * {@code minecraft_version=26.1.2}), not a guess at this version's newer RenderPipeline-based API.
 */
public final class WorldRenderUtils {

    private WorldRenderUtils() {
    }

    /** Draws a solid box, respecting depth (won't show through walls) - same choice Odin's Simon Says
     *  highlight uses by default, since this is meant to highlight a button you can already see. */
    public static void renderFilledBox(LevelRenderContext context, AABB box, float r, float g, float b, float a) {
        MultiBufferSource.BufferSource bufferSource = context.bufferSource();
        if (bufferSource == null) {
            return;
        }
        PoseStack poseStack = context.poseStack();
        Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().position();
        poseStack.pushPose();
        poseStack.translate(-cam.x, -cam.y, -cam.z);
        PoseStack.Pose pose = poseStack.last();
        VertexConsumer buffer = bufferSource.getBuffer(RenderTypes.debugFilledBox());

        float minX = (float) box.minX, minY = (float) box.minY, minZ = (float) box.minZ;
        float maxX = (float) box.maxX, maxY = (float) box.maxY, maxZ = (float) box.maxZ;
        addChainedFilledBoxVertices(pose, buffer, minX, minY, minZ, maxX, maxY, maxZ, r, g, b, a);

        poseStack.popPose();
    }

    /** Draws a wireframe outline box. */
    public static void renderOutlineBox(LevelRenderContext context, AABB box, float r, float g, float b, float a,
                                         float thickness) {
        MultiBufferSource.BufferSource bufferSource = context.bufferSource();
        if (bufferSource == null) {
            return;
        }
        PoseStack poseStack = context.poseStack();
        Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().position();
        poseStack.pushPose();
        poseStack.translate(-cam.x, -cam.y, -cam.z);
        PoseStack.Pose pose = poseStack.last();
        VertexConsumer buffer = bufferSource.getBuffer(RenderTypes.LINES_TRANSLUCENT);

        renderLineBox(pose, buffer, box, r, g, b, a, thickness);

        poseStack.popPose();
    }

    /** Draws a connected line strip through a real sequence of world-space points (e.g. a real puzzle
     *  solve path) - each consecutive pair of points gets one line segment, in order. */
    public static void renderLineStrip(LevelRenderContext context, java.util.List<Vec3> points,
                                        float r, float g, float b, float a, float thickness) {
        if (points.size() < 2) {
            return;
        }
        MultiBufferSource.BufferSource bufferSource = context.bufferSource();
        if (bufferSource == null) {
            return;
        }
        PoseStack poseStack = context.poseStack();
        Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().position();
        poseStack.pushPose();
        poseStack.translate(-cam.x, -cam.y, -cam.z);
        PoseStack.Pose pose = poseStack.last();
        VertexConsumer buffer = bufferSource.getBuffer(RenderTypes.LINES_TRANSLUCENT);

        for (int i = 0; i < points.size() - 1; i++) {
            Vec3 start = points.get(i);
            Vec3 end = points.get(i + 1);
            float sx = (float) start.x, sy = (float) start.y, sz = (float) start.z;
            float ex = (float) end.x, ey = (float) end.y, ez = (float) end.z;
            float dx = ex - sx, dy = ey - sy, dz = ez - sz;
            buffer.addVertex(pose, sx, sy, sz).setColor(r, g, b, a).setNormal(pose, dx, dy, dz).setLineWidth(thickness);
            buffer.addVertex(pose, ex, ey, ez).setColor(r, g, b, a).setNormal(pose, dx, dy, dz).setLineWidth(thickness);
        }

        poseStack.popPose();
    }

    private static final int[] EDGES = {
            0, 1, 1, 5, 5, 4, 4, 0,
            3, 2, 2, 6, 6, 7, 7, 3,
            0, 3, 1, 2, 5, 6, 4, 7
    };

    private static void renderLineBox(PoseStack.Pose pose, VertexConsumer buffer, AABB aabb,
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

    private static void addChainedFilledBoxVertices(PoseStack.Pose pose, VertexConsumer buffer,
                                                      float minX, float minY, float minZ,
                                                      float maxX, float maxY, float maxZ,
                                                      float r, float g, float b, float a) {
        var matrix = pose.pose();

        vertex(buffer, matrix, minX, minY, minZ, r, g, b, a);
        vertex(buffer, matrix, minX, minY, maxZ, r, g, b, a);
        vertex(buffer, matrix, minX, maxY, maxZ, r, g, b, a);
        vertex(buffer, matrix, minX, maxY, minZ, r, g, b, a);

        vertex(buffer, matrix, maxX, minY, maxZ, r, g, b, a);
        vertex(buffer, matrix, maxX, minY, minZ, r, g, b, a);
        vertex(buffer, matrix, maxX, maxY, minZ, r, g, b, a);
        vertex(buffer, matrix, maxX, maxY, maxZ, r, g, b, a);

        vertex(buffer, matrix, minX, minY, minZ, r, g, b, a);
        vertex(buffer, matrix, minX, maxY, minZ, r, g, b, a);
        vertex(buffer, matrix, maxX, maxY, minZ, r, g, b, a);
        vertex(buffer, matrix, maxX, minY, minZ, r, g, b, a);

        vertex(buffer, matrix, maxX, minY, maxZ, r, g, b, a);
        vertex(buffer, matrix, maxX, maxY, maxZ, r, g, b, a);
        vertex(buffer, matrix, minX, maxY, maxZ, r, g, b, a);
        vertex(buffer, matrix, minX, minY, maxZ, r, g, b, a);

        vertex(buffer, matrix, minX, minY, minZ, r, g, b, a);
        vertex(buffer, matrix, maxX, minY, minZ, r, g, b, a);
        vertex(buffer, matrix, maxX, minY, maxZ, r, g, b, a);
        vertex(buffer, matrix, minX, minY, maxZ, r, g, b, a);

        vertex(buffer, matrix, minX, maxY, maxZ, r, g, b, a);
        vertex(buffer, matrix, maxX, maxY, maxZ, r, g, b, a);
        vertex(buffer, matrix, maxX, maxY, minZ, r, g, b, a);
        vertex(buffer, matrix, minX, maxY, minZ, r, g, b, a);
    }

    private static void vertex(VertexConsumer buffer, org.joml.Matrix4f matrix, float x, float y, float z,
                                float r, float g, float b, float a) {
        buffer.addVertex(matrix, x, y, z).setColor(r, g, b, a);
    }

    /** Unpacks an ARGB int (as used by this mod's existing color-cycle settings) into 0-1 float
     *  components for the render methods above. */
    public static float[] argbToFloats(int argb) {
        float a = ((argb >> 24) & 0xFF) / 255f;
        float r = ((argb >> 16) & 0xFF) / 255f;
        float g = ((argb >> 8) & 0xFF) / 255f;
        float b = (argb & 0xFF) / 255f;
        if (a <= 0f) {
            a = 1f;
        }
        return new float[]{r, g, b, a};
    }
}
