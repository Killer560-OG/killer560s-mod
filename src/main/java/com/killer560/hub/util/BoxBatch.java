package com.killer560.hub.util;

import com.killer560.hub.compat.McRender;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.joml.Matrix4f;

/**
 * A reusable list of world-space boxes drawn in ONE {@link McRender#inCameraSpace} call per render type (FPS pass,
 * 2026-10-15). Callers that used to draw each box with its own call - a fill and an outline per mob - allocated a colour
 * array, a lambda, an AABB and a couple of Vec3 per box per frame; this keeps the boxes in one growing float array that
 * lives as long as its owner. Render thread only.
 *
 * <p>The vertices are exactly {@link WorldRenderUtils}' (and the ESP renderers' copies of them): the same six faces in
 * the same corner order for a fill, the same twelve edges for an outline. On 26.2 the callback runs later in the frame
 * ({@link McRender#CALLBACK_RUNS_LATER}), so the used part of the array is copied for it there; on 26.1.2 it is read in
 * place, and nothing is allocated but the call itself.
 */
public final class BoxBatch {

    /** Per box: minX minY minZ maxX maxY maxZ r g b a. */
    private static final int STRIDE = 10;

    private float[] data = new float[16 * STRIDE];
    private int count;
    // What the in-place geometry below reads (26.1.2 runs it inside inCameraSpace, before anything changes these).
    private float lineWidth;
    private final McRender.Geometry fillInPlace = (pose, buffer) -> emitFilled(pose, buffer, data, count);
    private final McRender.Geometry outlineInPlace = (pose, buffer) -> emitOutlines(pose, buffer, data, count, lineWidth);

    public void clear() {
        count = 0;
    }

    public int size() {
        return count;
    }

    public boolean isEmpty() {
        return count == 0;
    }

    public void add(double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
                    float r, float g, float b, float a) {
        int o = count * STRIDE;
        if (o + STRIDE > data.length) {
            data = java.util.Arrays.copyOf(data, data.length * 2);
        }
        float[] d = data;
        d[o] = (float) minX;
        d[o + 1] = (float) minY;
        d[o + 2] = (float) minZ;
        d[o + 3] = (float) maxX;
        d[o + 4] = (float) maxY;
        d[o + 5] = (float) maxZ;
        d[o + 6] = r;
        d[o + 7] = g;
        d[o + 8] = b;
        d[o + 9] = a;
        count++;
    }

    /** Every box filled, in one call. */
    public void drawFilled(LevelRenderContext context, RenderType type) {
        if (count == 0) {
            return;
        }
        if (McRender.CALLBACK_RUNS_LATER) {
            float[] snap = java.util.Arrays.copyOf(data, count * STRIDE);
            int n = count;
            McRender.inCameraSpace(context, type, (pose, buffer) -> emitFilled(pose, buffer, snap, n));
        } else {
            McRender.inCameraSpace(context, type, fillInPlace);
        }
    }

    /** Every box outlined, {@code width} wide, in one call. */
    public void drawOutlines(LevelRenderContext context, RenderType type, float width) {
        if (count == 0) {
            return;
        }
        if (McRender.CALLBACK_RUNS_LATER) {
            float[] snap = java.util.Arrays.copyOf(data, count * STRIDE);
            int n = count;
            McRender.inCameraSpace(context, type, (pose, buffer) -> emitOutlines(pose, buffer, snap, n, width));
        } else {
            lineWidth = width;
            McRender.inCameraSpace(context, type, outlineInPlace);
        }
    }

    /** The six faces, WorldRenderUtils.addChainedFilledBoxVertices' corner order. */
    private static void emitFilled(PoseStack.Pose pose, VertexConsumer buf, float[] d, int n) {
        Matrix4f m = pose.pose();
        for (int i = 0; i < n; i++) {
            int o = i * STRIDE;
            float x0 = d[o], y0 = d[o + 1], z0 = d[o + 2], x1 = d[o + 3], y1 = d[o + 4], z1 = d[o + 5];
            float r = d[o + 6], g = d[o + 7], b = d[o + 8], a = d[o + 9];
            buf.addVertex(m, x0, y0, z0).setColor(r, g, b, a);
            buf.addVertex(m, x0, y0, z1).setColor(r, g, b, a);
            buf.addVertex(m, x0, y1, z1).setColor(r, g, b, a);
            buf.addVertex(m, x0, y1, z0).setColor(r, g, b, a);

            buf.addVertex(m, x1, y0, z1).setColor(r, g, b, a);
            buf.addVertex(m, x1, y0, z0).setColor(r, g, b, a);
            buf.addVertex(m, x1, y1, z0).setColor(r, g, b, a);
            buf.addVertex(m, x1, y1, z1).setColor(r, g, b, a);

            buf.addVertex(m, x0, y0, z0).setColor(r, g, b, a);
            buf.addVertex(m, x0, y1, z0).setColor(r, g, b, a);
            buf.addVertex(m, x1, y1, z0).setColor(r, g, b, a);
            buf.addVertex(m, x1, y0, z0).setColor(r, g, b, a);

            buf.addVertex(m, x1, y0, z1).setColor(r, g, b, a);
            buf.addVertex(m, x1, y1, z1).setColor(r, g, b, a);
            buf.addVertex(m, x0, y1, z1).setColor(r, g, b, a);
            buf.addVertex(m, x0, y0, z1).setColor(r, g, b, a);

            buf.addVertex(m, x0, y0, z0).setColor(r, g, b, a);
            buf.addVertex(m, x1, y0, z0).setColor(r, g, b, a);
            buf.addVertex(m, x1, y0, z1).setColor(r, g, b, a);
            buf.addVertex(m, x0, y0, z1).setColor(r, g, b, a);

            buf.addVertex(m, x0, y1, z1).setColor(r, g, b, a);
            buf.addVertex(m, x1, y1, z1).setColor(r, g, b, a);
            buf.addVertex(m, x1, y1, z0).setColor(r, g, b, a);
            buf.addVertex(m, x0, y1, z0).setColor(r, g, b, a);
        }
    }

    /** Corners 0-7 are x0y0z0, x1y0z0, x1y1z0, x0y1z0, then the same at z1; the twelve edges between them. */
    private static final int[] EDGES = {
            0, 1, 1, 5, 5, 4, 4, 0,
            3, 2, 2, 6, 6, 7, 7, 3,
            0, 3, 1, 2, 5, 6, 4, 7
    };

    private static void emitOutlines(PoseStack.Pose pose, VertexConsumer buf, float[] d, int n, float width) {
        for (int i = 0; i < n; i++) {
            int o = i * STRIDE;
            float x0 = d[o], y0 = d[o + 1], z0 = d[o + 2], x1 = d[o + 3], y1 = d[o + 4], z1 = d[o + 5];
            float r = d[o + 6], g = d[o + 7], b = d[o + 8], a = d[o + 9];
            for (int e = 0; e < EDGES.length; e += 2) {
                int k0 = EDGES[e];
                int k1 = EDGES[e + 1];
                float sx = cx(k0, x0, x1), sy = (k0 & 3) >= 2 ? y1 : y0, sz = k0 >= 4 ? z1 : z0;
                float ex = cx(k1, x0, x1), ey = (k1 & 3) >= 2 ? y1 : y0, ez = k1 >= 4 ? z1 : z0;
                float dx = ex - sx, dy = ey - sy, dz = ez - sz;
                buf.addVertex(pose, sx, sy, sz).setColor(r, g, b, a).setNormal(pose, dx, dy, dz).setLineWidth(width);
                buf.addVertex(pose, ex, ey, ez).setColor(r, g, b, a).setNormal(pose, dx, dy, dz).setLineWidth(width);
            }
        }
    }

    private static float cx(int k, float x0, float x1) {
        int m = k & 3;
        return m == 1 || m == 2 ? x1 : x0;
    }
}
