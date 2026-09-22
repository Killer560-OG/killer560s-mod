package com.killer560.hub.puzzlesolvers;

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

import java.util.List;
import java.util.Optional;

/**
 * Every puzzle/boss solver's world drawing goes through here instead of straight to
 * {@link WorldRenderUtils}, so one setting ({@link SolverEspConfig#isThroughWalls()}) decides whether a
 * solver's answer is visible through blocks - killer560, 2026-09-20: "make all solvers ESP based for the
 * waypoints".
 * <p>
 * Signatures mirror {@code WorldRenderUtils}' exactly so each solver's render method only changed class
 * name. With the setting off every call falls straight back through to {@code WorldRenderUtils} and draws
 * exactly what it drew before. With it on, the box/line goes to a copy of vanilla's LINES /
 * DEBUG_FILLED pipeline with the depth-stencil state removed - the same construction
 * {@code mobesp/EspRenderer} and {@code p3nav/P3NavRenderer} already use (NoammAddons' 26.1.2
 * {@code NoammRenderPipelines}: {@code withDepthStencilState(Optional.empty())}). The pipeline ids are
 * solver-specific so they cannot collide with either of those two registrations.
 */
public final class SolverEspRender {

    private SolverEspRender() {
    }

    /** Called from each solver's {@code register()} (client init) so the pipelines are in
     *  {@code RenderPipelines}' static list before the renderer precompiles it - same reason
     *  {@code EspRenderer.init()} exists. Safe to call any number of times. */
    public static void init() {
        RenderType unused = ThroughWalls.LINES;
    }

    /** Holder idiom - built once, on {@link #init()}. */
    private static final class ThroughWalls {
        // killer560, 2026-09-20 (Blaze report): "lines look weird if there is water behind them - they
        // read as being behind the water". FILLED already called .sortOnUpload() below; LINES didn't, so
        // a solver's line (Blaze's kill-order lines, Water Board's/Teleport Maze's tracers, Ice Fill/Ice
        // Path's path lines...) could get flushed to the GPU in an arbitrary order relative to vanilla's
        // own translucent water pass despite having no depth test, letting water's alpha blend on top of
        // an already-drawn line instead of the other way around. Matches FILLED's own sorted buffer now.
        static final RenderType LINES = RenderType.create("killer560smod_solver_lines_through_walls",
                RenderSetup.builder(RenderPipelines.register(RenderPipeline.builder(RenderPipelines.LINES_SNIPPET)
                        .withLocation(Identifier.fromNamespaceAndPath("killer560smod", "pipeline/solver_lines_through_walls"))
                        .withCull(false)
                        .withDepthStencilState(Optional.<DepthStencilState>empty())
                        .build())).sortOnUpload().createRenderSetup());

        static final RenderType FILLED = RenderType.create("killer560smod_solver_filled_through_walls",
                RenderSetup.builder(RenderPipelines.register(RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
                        .withLocation(Identifier.fromNamespaceAndPath("killer560smod", "pipeline/solver_filled_through_walls"))
                        .withCull(false)
                        .withDepthStencilState(Optional.<DepthStencilState>empty())
                        .build())).sortOnUpload().createRenderSetup());
    }

    private static boolean throughWalls() {
        return SolverEspConfig.getInstance().isThroughWalls();
    }

    /** A solver box sits exactly on the block faces it is outlining, so with the depth test on the two
     *  coincide and z-fight into nothing. Nudging it out by half a millimetre costs nothing visually and
     *  makes the outline actually land in front of the block. */
    private static final double Z_FIGHT_NUDGE = 0.005;

    public static void renderOutlineBox(LevelRenderContext context, AABB box, float r, float g, float b, float a,
                                        float thickness) {
        if (!throughWalls()) {
            WorldRenderUtils.renderOutlineBox(context, box.inflate(Z_FIGHT_NUDGE), r, g, b, a, thickness);
            return;
        }
        MultiBufferSource.BufferSource buffers = context.bufferSource();
        if (buffers == null) {
            return;
        }
        PoseStack poseStack = context.poseStack();
        Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().position();
        poseStack.pushPose();
        poseStack.translate(-cam.x, -cam.y, -cam.z);
        lineBox(poseStack.last(), buffers.getBuffer(ThroughWalls.LINES), box.inflate(Z_FIGHT_NUDGE),
                r, g, b, a, thickness);
        poseStack.popPose();
    }

    /** A solver waypoint in the shared Waypoint Style - an outline, or a translucent full block. */
    public static void renderWaypoint(LevelRenderContext context, AABB box, float r, float g, float b, float thickness) {
        if (SolverEspConfig.getInstance().getWaypointStyle() == SolverEspConfig.WaypointStyle.FULL) {
            renderFilledBox(context, box, r, g, b, 0.45f);
        } else {
            renderOutlineBox(context, box, r, g, b, 1f, thickness);
        }
    }

    public static void renderFilledBox(LevelRenderContext context, AABB box, float r, float g, float b, float a) {
        if (!throughWalls()) {
            WorldRenderUtils.renderFilledBox(context, box.inflate(Z_FIGHT_NUDGE), r, g, b, a);
            return;
        }
        MultiBufferSource.BufferSource buffers = context.bufferSource();
        if (buffers == null) {
            return;
        }
        PoseStack poseStack = context.poseStack();
        Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().position();
        poseStack.pushPose();
        poseStack.translate(-cam.x, -cam.y, -cam.z);
        filledBox(poseStack.last().pose(), buffers.getBuffer(ThroughWalls.FILLED), box.inflate(Z_FIGHT_NUDGE),
                r, g, b, a);
        poseStack.popPose();
    }

    public static void renderLineStrip(LevelRenderContext context, List<Vec3> points,
                                       float r, float g, float b, float a, float thickness) {
        if (!throughWalls()) {
            WorldRenderUtils.renderLineStrip(context, points, r, g, b, a, thickness);
            return;
        }
        if (points.size() < 2) {
            return;
        }
        MultiBufferSource.BufferSource buffers = context.bufferSource();
        if (buffers == null) {
            return;
        }
        PoseStack poseStack = context.poseStack();
        Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().position();
        poseStack.pushPose();
        poseStack.translate(-cam.x, -cam.y, -cam.z);
        PoseStack.Pose pose = poseStack.last();
        VertexConsumer buffer = buffers.getBuffer(ThroughWalls.LINES);
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
