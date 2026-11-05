package com.killer560.hub.util;

import com.killer560.hub.compat.McRender;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.joml.Matrix4f;

/**
 * A reusable list of world-space boxes drawn in ONE {@link McRender#inCameraSpace} call per render type (FPS pass,
 * 2026-10-15). Callers that used to draw each box with its own call - a fill and an outline per mob - allocated a color
 * array, a lambda, an AABB and a couple of Vec3 per box per frame; this keeps the boxes in one growing float array that
 * lives as long as its owner. Render thread only.
 *
 * <p>The vertices are exactly {@link WorldRenderUtils}' (and the ESP renderers' copies of them): the same six faces in
 * the same corner order for a fill, the same twelve edges for an outline. The callback runs later in the frame
 * ({@link McRender#CALLBACK_RUNS_LATER}), so the used part is copied into a pooled buffer (boxbatch-1105).
 */
public final class BoxBatch {

    /** Per box: minX minY minZ maxX maxY maxZ r g b a. */
    private static final int STRIDE = 10;

    private float[] data = new float[16 * STRIDE];
    private int count;

    public void clear() { count = 0; }

    public int size() { return count; }

    public boolean isEmpty() { return count == 0; }

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

    /**
     * The callback runs later in the frame (both 26.x), after this batch may be cleared and refilled, so it gets a copy of
     * the used part. The copy lives in a pooled {@link Snap} that hands itself back once drawn: no allocation per draw.
     */
    private static final java.util.ArrayDeque<Snap> POOL = new java.util.ArrayDeque<>();
    private static final int POOL_MAX = 8;

    private static final class Snap implements McRender.Geometry {
        float[] d = new float[16 * STRIDE];
        int n;
        float width;
        boolean outline;
        boolean held;

        @Override
        public void draw(PoseStack.Pose pose, VertexConsumer buf) {
            if (!held) return;
            if (outline) emitOutlines(pose, buf, d, n, width); else emitFilled(pose, buf, d, n);
            held = false;
            synchronized (POOL) {
                if (POOL.size() < POOL_MAX) POOL.push(this);
            }
        }
    }

    private void submit(LevelRenderContext context, RenderType type, boolean outline, float width) {
        Snap s;
        synchronized (POOL) {
            s = POOL.poll();
        }
        if (s == null) s = new Snap();
        int len = count * STRIDE;
        if (s.d.length < len) s.d = new float[Math.max(len, s.d.length * 2)];
        System.arraycopy(data, 0, s.d, 0, len);
        s.n = count;
        s.width = width;
        s.outline = outline;
        s.held = true;
        McRender.inCameraSpace(context, type, s);
    }

    /** Every box filled, in one call. */
    public void drawFilled(LevelRenderContext context, RenderType type) {
        if (count != 0) submit(context, type, false, 0f);
    }

    /** Every box outlined, {@code width} wide, in one call. */
    public void drawOutlines(LevelRenderContext context, RenderType type, float width) {
        if (count != 0) submit(context, type, true, width);
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
