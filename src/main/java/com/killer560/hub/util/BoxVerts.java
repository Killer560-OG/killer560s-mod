package com.killer560.hub.util;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;

/** Box vertices shared by the ESP renderers that draw into their own buffers (render thread only). */
public final class BoxVerts {

    private BoxVerts() {
    }

    private static final int[] EDGES = {
            0, 1, 1, 5, 5, 4, 4, 0,
            3, 2, 2, 6, 6, 7, 7, 3,
            0, 3, 1, 2, 5, 6, 4, 7
    };

    private static final float[] CORNERS = new float[24];

    /** ARGB to {r, g, b, a}; alpha 0 reads as opaque, then scaled by {@code alphaScale}. */
    public static float[] rgba(int argb, float alphaScale) {
        float a = ((argb >>> 24) & 0xFF) / 255f;
        if (a <= 0f) {
            a = 1f;
        }
        return new float[]{((argb >> 16) & 0xFF) / 255f, ((argb >> 8) & 0xFF) / 255f, (argb & 0xFF) / 255f, a * alphaScale};
    }

    public static void lineBox(PoseStack.Pose pose, VertexConsumer buffer, AABB b, float r, float g, float bl, float a,
                               float width) {
        float x0 = (float) b.minX, y0 = (float) b.minY, z0 = (float) b.minZ;
        float x1 = (float) b.maxX, y1 = (float) b.maxY, z1 = (float) b.maxZ;
        // Render thread only: one reused array instead of a new one per box per frame.
        float[] corners = CORNERS;
        corners[0] = x0; corners[1] = y0; corners[2] = z0;
        corners[3] = x1; corners[4] = y0; corners[5] = z0;
        corners[6] = x1; corners[7] = y1; corners[8] = z0;
        corners[9] = x0; corners[10] = y1; corners[11] = z0;
        corners[12] = x0; corners[13] = y0; corners[14] = z1;
        corners[15] = x1; corners[16] = y0; corners[17] = z1;
        corners[18] = x1; corners[19] = y1; corners[20] = z1;
        corners[21] = x0; corners[22] = y1; corners[23] = z1;
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

    public static void filledBox(Matrix4f m, VertexConsumer buf, AABB box, float r, float g, float b, float a) {
        float x0 = (float) box.minX, y0 = (float) box.minY, z0 = (float) box.minZ;
        float x1 = (float) box.maxX, y1 = (float) box.maxY, z1 = (float) box.maxZ;
        quad(m, buf, r, g, b, a, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0);
        quad(m, buf, r, g, b, a, x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1);
        quad(m, buf, r, g, b, a, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0);
        quad(m, buf, r, g, b, a, x1, y0, z1, x1, y1, z1, x0, y1, z1, x0, y0, z1);
        quad(m, buf, r, g, b, a, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);
        quad(m, buf, r, g, b, a, x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0);
    }

    private static void quad(Matrix4f m, VertexConsumer buf, float r, float g, float b, float a,
                             float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float dx, float dy, float dz) {
        buf.addVertex(m, ax, ay, az).setColor(r, g, b, a);
        buf.addVertex(m, bx, by, bz).setColor(r, g, b, a);
        buf.addVertex(m, cx, cy, cz).setColor(r, g, b, a);
        buf.addVertex(m, dx, dy, dz).setColor(r, g, b, a);
    }
}
