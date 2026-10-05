package com.killer560.hub.util;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import com.killer560.hub.compat.McRender;

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
        McRender.inCameraSpace(context, RenderTypes.debugFilledBox(), (pose, buffer) -> {

            float minX = (float) box.minX, minY = (float) box.minY, minZ = (float) box.minZ;
            float maxX = (float) box.maxX, maxY = (float) box.maxY, maxZ = (float) box.maxZ;
            addChainedFilledBoxVertices(pose, buffer, minX, minY, minZ, maxX, maxY, maxZ, r, g, b, a);
        });
    }

    /**
     * Draws a solid upright cylinder (sides and both caps) around a vertical axis, depth-tested like
     * {@link #renderFilledBox}. Every face is emitted in both windings so it reads the same from inside and outside
     * whatever the pipeline's culling; a cap is a fan of quads whose last two corners coincide (one triangle each),
     * which is how a disc is drawn in a QUADS format. Nothing is drawn outside the circle, unlike a box fill.
     */
    public static void renderFilledCylinder(LevelRenderContext context, Vec3 centre, double radius, double y0, double y1,
                                            int segments, float r, float g, float b, float a) {
        int n = Math.max(3, segments);
        McRender.inCameraSpace(context, RenderTypes.debugFilledBox(), (pose, buffer) -> {
            var matrix = pose.pose();
            float cx = (float) centre.x, cz = (float) centre.z;
            float bottom = (float) y0, top = (float) y1;
            for (int i = 0; i < n; i++) {
                double t0 = (Math.PI * 2 * i) / n;
                double t1 = (Math.PI * 2 * (i + 1)) / n;
                float x0 = (float) (centre.x + Math.cos(t0) * radius), z0 = (float) (centre.z + Math.sin(t0) * radius);
                float x1 = (float) (centre.x + Math.cos(t1) * radius), z1 = (float) (centre.z + Math.sin(t1) * radius);
                // side
                twoSidedQuad(buffer, matrix, x0, bottom, z0, x1, bottom, z1, x1, top, z1, x0, top, z0, r, g, b, a);
                // caps
                twoSidedQuad(buffer, matrix, cx, top, cz, x0, top, z0, x1, top, z1, x1, top, z1, r, g, b, a);
                if (top - bottom > 1.0e-4f) {
                    twoSidedQuad(buffer, matrix, cx, bottom, cz, x0, bottom, z0, x1, bottom, z1, x1, bottom, z1, r, g, b, a);
                }
            }
        });
    }

    private static void twoSidedQuad(VertexConsumer buffer, org.joml.Matrix4f matrix,
                                     float ax, float ay, float az, float bx, float by, float bz,
                                     float cx, float cy, float cz, float dx, float dy, float dz,
                                     float r, float g, float b, float a) {
        vertex(buffer, matrix, ax, ay, az, r, g, b, a);
        vertex(buffer, matrix, bx, by, bz, r, g, b, a);
        vertex(buffer, matrix, cx, cy, cz, r, g, b, a);
        vertex(buffer, matrix, dx, dy, dz, r, g, b, a);
        vertex(buffer, matrix, dx, dy, dz, r, g, b, a);
        vertex(buffer, matrix, cx, cy, cz, r, g, b, a);
        vertex(buffer, matrix, bx, by, bz, r, g, b, a);
        vertex(buffer, matrix, ax, ay, az, r, g, b, a);
    }

    /** Draws a wireframe outline box. */
    public static void renderOutlineBox(LevelRenderContext context, AABB box, float r, float g, float b, float a,
                                         float thickness) {
        McRender.inCameraSpace(context, RenderTypes.LINES_TRANSLUCENT, (pose, buffer) -> {

            renderLineBox(pose, buffer, box, r, g, b, a, thickness);
        });
    }

    /**
     * Where a player-anchored tracer line should actually start so it reads as leaving the crosshair.
     * <p>
     * killer560, 2026-09-27: "Door keys tracer line is still jacked up" / "the wwither tracer ... looks like
     * it is being drawn to my players head instead of their crosshair" - a line built from
     * {@code Entity#getEyePosition()} anchors to the PLAYER, which is only repositioned once per game tick,
     * while the camera itself moves every frame (interpolated, plus anything else that offsets it from the
     * raw eye position). At any FPS above the tick rate that mismatch reads as the line lagging behind and
     * swinging from the wrong spot - "attached to the head" - instead of tracking the crosshair.
     * <p>
     * Anchoring to the camera's own position fixes that, but a line that starts exactly AT the camera looks
     * straight down its own length from the viewer's eye and can collapse to a dot at some angles - the same
     * thing killer560 hit in {@code puzzlesolvers.TeleportMazeSolverFeature} (2026-09-21: "the line ... doesn't
     * really show up"). So this nudges the origin half a block forward along the camera's own look vector
     * first, exactly like that fix and {@code routes.WaypointRoutesFeature}'s "Line to Next" line already do -
     * this is just the shared version of both, for every other tracer to call instead of re-deriving it.
     */
    public static Vec3 tracerOrigin(LevelRenderContext context) {
        return McRender.cameraPos(context).add(Vec3.directionFromRotation(
                McRender.cameraXRot(context), McRender.cameraYRot(context)).scale(0.5));
    }

    /** Draws a connected line strip through a real sequence of world-space points (e.g. a real puzzle
     *  solve path) - each consecutive pair of points gets one line segment, in order. */
    public static void renderLineStrip(LevelRenderContext context, java.util.List<Vec3> points,
                                        float r, float g, float b, float a, float thickness) {
        if (points.size() < 2) {
            return;
        }
        // Copied now: on 26.2 the callback runs later in the frame, and callers pass live path lists the tick
        // rewrites (Ice Fill's currentPath, the solvers' routes).
        final Vec3[] pts = points.toArray(new Vec3[0]);
        McRender.inCameraSpace(context, RenderTypes.LINES_TRANSLUCENT, (pose, buffer) -> {

            for (int i = 0; i < pts.length - 1; i++) {
                Vec3 start = pts[i];
                Vec3 end = pts[i + 1];
                float sx = (float) start.x, sy = (float) start.y, sz = (float) start.z;
                float ex = (float) end.x, ey = (float) end.y, ez = (float) end.z;
                float dx = ex - sx, dy = ey - sy, dz = ez - sz;
                buffer.addVertex(pose, sx, sy, sz).setColor(r, g, b, a).setNormal(pose, dx, dy, dz).setLineWidth(thickness);
                buffer.addVertex(pose, ex, ey, ez).setColor(r, g, b, a).setNormal(pose, dx, dy, dz).setLineWidth(thickness);
            }
        });
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
