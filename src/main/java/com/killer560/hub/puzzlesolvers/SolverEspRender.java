package com.killer560.hub.puzzlesolvers;

import com.killer560.hub.util.WorldRenderUtils;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.List;
import java.util.Optional;
import com.killer560.hub.compat.McRender;

/**
 * Every puzzle/boss solver's world drawing goes through here instead of straight to
 * {@link WorldRenderUtils}, so the solver's own setting ({@link SolverEspConfig#isThroughWalls(SolverEspConfig.Solver)})
 * decides whether its answer is visible through blocks - killer560, 2026-09-20: "make all solvers ESP based for the
 * waypoints"; per solver since 2026-10-18 (each call names its solver, first argument).
 * <p>
 * Signatures mirror {@code WorldRenderUtils}' so each solver's render method only changed class name. Both modes draw through
 * this class's own render types (copies of vanilla's LINES / DEBUG_FILLED pipelines that WRITE depth: with the setting off
 * they keep the depth test, with it on the depth state is ALWAYS_PASS), because on 26.3 water is drawn after the box and a box
 * that wrote no depth had water behind it painted over it (killer560, 2026-11-03; docs/LESSONS-GUI.md "Solver boxes and water"). The
 * through-walls construction is the same as {@code mobesp/EspRenderer} and {@code p3nav/P3NavRenderer} (NoammAddons' 26.1.2
 * {@code NoammRenderPipelines}: {@code withDepthStencilState}). The pipeline ids are solver-specific so they cannot collide.
 */
public final class SolverEspRender {

    private SolverEspRender() {
    }

    /** Called from each solver's {@code register()} (client init) so the pipelines are in
     *  {@code RenderPipelines}' static list before the renderer precompiles it - same reason
     *  {@code EspRenderer.init()} exists. Safe to call any number of times. */
    public static void init() {
        RenderType unused = ThroughWalls.LINES;
        unused = Depth.LINES;
    }

    /** Holder idiom - built once, on {@link #init()}. */
    private static final class ThroughWalls {
        // killer560, 2026-09-20 (Blaze report): "lines look weird if there is water behind them - they
        // read as being behind the water". FILLED already called .sortOnUpload() below; LINES didn't, so
        // a solver's line (Blaze's kill-order lines, Water Board's/Teleport Maze's tracers, Ice Fill/Ice
        // Path's path lines...) could get flushed to the GPU in an arbitrary order relative to vanilla's
        // own translucent water pass despite having no depth test, letting water's alpha blend on top of
        // an already-drawn line instead of the other way around. Matches FILLED's own sorted buffer now - on 26.1.2
        // only: 26.2 refuses to sort anything but quads and crashed the frame (McRender.sortLinesOnUpload).
        /** Passes every fragment and WRITES depth: the water pass runs after the features pass on 26.3 (killer560, 2026-11-03,
         *  blaze report), and a box that wrote no depth had water behind it blended over it. With the box's depth in the buffer,
         *  water behind fails its depth test and water in front still tints it. */
        private static final Optional<DepthStencilState> WRITE_DEPTH_ALWAYS = Optional.of(new DepthStencilState(
                com.mojang.renderpearl.api.pipeline.CompareOp.ALWAYS_PASS, true));

        private static final RenderPipeline.Builder LINES_BASE = com.killer560.hub.compat.McRender.linesColorTarget(RenderPipeline.builder(RenderPipelines.LINES_SNIPPET))
                .withLocation(Identifier.fromNamespaceAndPath("killer560smod", "pipeline/solver_lines_through_walls"))
                .withCull(false)
                .withDepthStencilState(WRITE_DEPTH_ALWAYS);
        private static final RenderPipeline.Builder FILLED_BASE = RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
                .withLocation(Identifier.fromNamespaceAndPath("killer560smod", "pipeline/solver_filled_through_walls"))
                .withCull(false)
                .withDepthStencilState(WRITE_DEPTH_ALWAYS);

        // OIT pipelines (26.3, Improved Transparency on): without them the first blended draw throws; see McRender.withOitNoDepth.
        static final RenderType LINES = RenderType.create("killer560smod_solver_lines_through_walls",
                McRender.withOitNoDepth(McRender.sortLinesOnUpload(RenderSetup.builder(RenderPipelines.register(LINES_BASE.build()))),
                        "killer560smod_solver_lines", true).createRenderSetup());

        static final RenderType FILLED = RenderType.create("killer560smod_solver_filled_through_walls",
                McRender.withOitNoDepth(RenderSetup.builder(RenderPipelines.register(FILLED_BASE.build())).sortOnUpload(),
                        "killer560smod_solver_filled", false).createRenderSetup());
    }

    /** Holder idiom - the depth-tested twins: same as vanilla's debug box and lines but WRITING depth (see ThroughWalls.WRITE_DEPTH_ALWAYS). */
    private static final class Depth {
        static final RenderType LINES = RenderType.create("killer560smod_solver_lines_depth",
                McRender.withVanillaOit(McRender.sortLinesOnUpload(RenderSetup.builder(RenderPipelines.register(
                        com.killer560.hub.compat.McRender.linesColorTarget(RenderPipeline.builder(RenderPipelines.LINES_SNIPPET))
                                .withLocation(Identifier.fromNamespaceAndPath("killer560smod", "pipeline/solver_lines_depth"))
                                .withCull(false)
                                .withDepthStencilState(DepthStencilState.DEFAULT)
                                .build()))), true).createRenderSetup());

        static final RenderType FILLED = RenderType.create("killer560smod_solver_filled_depth",
                McRender.withVanillaOit(RenderSetup.builder(RenderPipelines.register(RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
                        .withLocation(Identifier.fromNamespaceAndPath("killer560smod", "pipeline/solver_filled_depth"))
                        .withCull(false)
                        .withDepthStencilState(DepthStencilState.DEFAULT)
                        .build())).sortOnUpload(), false).createRenderSetup());
    }

    /** The solver's own Highlight switch: off, the methods below draw nothing for it. */
    private static boolean off(SolverEspConfig.Solver solver) {
        boolean off = !SolverEspConfig.getInstance().isEnabled(solver);
        noteMode(solver, off);
        return off;
    }

    private static final org.slf4j.Logger LOGGER = com.killer560.hub.util.ModLog.get("killer560smod-puzzlesolvers");
    /** Logging only: the draw mode last logged per solver (by ordinal; -1 never logged), so a line is written when it
     *  CHANGES. Kept as ints so the per-frame check allocates nothing. */
    private static final int[] lastMode = new int[SolverEspConfig.Solver.values().length];

    static {
        java.util.Arrays.fill(lastMode, -1);
    }

    private static void noteMode(SolverEspConfig.Solver solver, boolean off) {
        if (solver == null) {
            return;
        }
        boolean through = !off && throughWalls(solver);
        SolverEspConfig.WaypointStyle style = off ? null : SolverEspConfig.getInstance().getWaypointStyle(solver);
        int code = off ? 0 : 1 + (through ? 1 : 0) + 2 * (style == null ? 0 : style.ordinal() + 1);
        int i = solver.ordinal();
        if (lastMode[i] == code) {
            return;
        }
        lastMode[i] = code;
        LOGGER.info("[SolverEsp] {} draws: {}", solver, off ? "highlight off - nothing drawn"
                : (through ? "through walls" : "depth-tested") + ", waypoint style " + style);
    }

    private static boolean throughWalls(SolverEspConfig.Solver solver) {
        // Livid's through-walls highlight is cheat-only (killer560, 2026-10-18): a legit build never draws it through blocks.
        if (solver == SolverEspConfig.Solver.LIVID && !com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            return false;
        }
        return SolverEspConfig.getInstance().isThroughWalls(solver);
    }

    /** A solver box sits exactly on the block faces it is outlining, so with the depth test on the two
     *  coincide and z-fight into nothing. Nudging it out by half a millimeter costs nothing visually and
     *  makes the outline actually land in front of the block. */
    private static final double Z_FIGHT_NUDGE = 0.005;

    public static void renderOutlineBox(SolverEspConfig.Solver solver, LevelRenderContext context, AABB box, float r, float g,
                                        float b, float a, float thickness) {
        if (off(solver)) {
            return;
        }
        McRender.inCameraSpace(context, throughWalls(solver) ? ThroughWalls.LINES : Depth.LINES, (pose, buffer) ->
                lineBox(pose, buffer, box.inflate(Z_FIGHT_NUDGE), r, g, b, a, thickness));
    }

    /** A solver waypoint in the solver's Waypoint Style - an outline, or a translucent full block. */
    public static void renderWaypoint(SolverEspConfig.Solver solver, LevelRenderContext context, AABB box, float r, float g,
                                      float b, float thickness) {
        if (off(solver)) {
            return;
        }
        if (SolverEspConfig.getInstance().getWaypointStyle(solver) == SolverEspConfig.WaypointStyle.FULL) {
            renderFilledBox(solver, context, box, r, g, b, 0.45f);
        } else {
            renderOutlineBox(solver, context, box, r, g, b, 1f, thickness);
        }
    }

    public static void renderFilledBox(SolverEspConfig.Solver solver, LevelRenderContext context, AABB box, float r, float g,
                                       float b, float a) {
        if (off(solver)) {
            return;
        }
        McRender.inCameraSpace(context, throughWalls(solver) ? ThroughWalls.FILLED : Depth.FILLED, (pose, buffer) ->
                filledBox(pose.pose(), buffer, box.inflate(Z_FIGHT_NUDGE), r, g, b, a));
    }

    public static void renderLineStrip(SolverEspConfig.Solver solver, LevelRenderContext context, List<Vec3> points,
                                       float r, float g, float b, float a, float thickness) {
        if (off(solver)) {
            return;
        }
        if (points.size() < 2) {
            return;
        }
        // Copied now: on 26.2 the callback runs later in the frame, and callers pass live path lists.
        final Vec3[] pts = points.toArray(new Vec3[0]);
        McRender.inCameraSpace(context, throughWalls(solver) ? ThroughWalls.LINES : Depth.LINES, (pose, buffer) -> {
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
