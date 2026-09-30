package com.killer560.hub.etherwarp;

import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import com.killer560.hub.util.ModLog;

import java.util.List;
import java.util.Optional;
import com.killer560.hub.compat.McRender;

/**
 * Box drawing for Etherwarp Waypoints - killer560, 2026-09-27: "Treat them essentially as secret waypoints
 * with how that works," so this is a deliberately small copy of
 * {@code com.killer560.hub.secretwaypoints.SecretWaypointsRenderer}: same no-depth "through walls" pipeline
 * construction (its own pipeline ids so they can't collide with that package's, or with P3 Nav's/Dungeon
 * ESP's), same fill/outline vertex layout, and the same "one getBuffer + one full pass PER render type,
 * never interleaved per box" rule that renderer's crash note explains (a level {@code BufferSource} only has
 * a few fixed buffers - the fill and outline types here are not among them, so both share its one SHARED
 * buffer, and alternating types mid-loop ends one builder out from under the other).
 * <p>
 * Left out on purpose: that renderer's scratch-array distance-cull batching. It exists there because a
 * dungeon floor's secret waypoints can be well over a hundred boxes a frame; an etherwarp waypoint list is
 * whatever killer560 personally placed in the one room he's standing in - a handful at most - so a plain
 * per-box loop costs nothing worth optimising away.
 */
final class EtherwarpWaypointsRenderer {

    private static final Logger LOGGER = ModLog.get("killer560smod-etherwarp");

    private EtherwarpWaypointsRenderer() {
    }

    /** One box to draw - built by {@link EtherwarpFeature} for the waypoints of the room you're in. */
    record Entry(AABB box, float r, float g, float b, float a) {
    }

    /** Called from {@link EtherwarpFeature#register()} so the pipelines exist before the renderer's first draw. */
    static void init() {
        RenderType unused = ThroughWalls.LINES;
    }

    /** Holder idiom - built once, on {@link #init()}. */
    private static final class ThroughWalls {
        static final RenderType LINES = RenderType.create("killer560smod_etherwarp_lines_through_walls",
                RenderSetup.builder(RenderPipelines.register(RenderPipeline.builder(RenderPipelines.LINES_SNIPPET)
                        .withLocation(Identifier.fromNamespaceAndPath("killer560smod",
                                "pipeline/etherwarp_lines_through_walls"))
                        .withCull(false)
                        .withDepthStencilState(Optional.<DepthStencilState>empty())
                        .build())).createRenderSetup());

        static final RenderType FILLED = RenderType.create("killer560smod_etherwarp_filled_through_walls",
                RenderSetup.builder(RenderPipelines.register(RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
                        .withLocation(Identifier.fromNamespaceAndPath("killer560smod",
                                "pipeline/etherwarp_filled_through_walls"))
                        .withCull(false)
                        .withDepthStencilState(Optional.<DepthStencilState>empty())
                        .build())).sortOnUpload().createRenderSetup());
    }

    static void draw(LevelRenderContext context, List<Entry> entries, EtherwarpWaypointsConfig.Style style,
                     boolean throughWalls) {
        if (entries.isEmpty()) {
            return;
        }
        boolean fill = style != EtherwarpWaypointsConfig.Style.OUTLINE;
        boolean outline = style != EtherwarpWaypointsConfig.Style.FILLED;
        float fillAlphaScale = style == EtherwarpWaypointsConfig.Style.FILL_AND_OUTLINE ? 0.5f : 1.0f;

        try {
            // Two flat passes, never interleaved - see this class's doc for why. Two separate inCameraSpace
            // calls keep that: one render type each, one uninterrupted pass each.
            if (fill) {
                McRender.inCameraSpace(context,
                        throughWalls ? ThroughWalls.FILLED : RenderTypes.debugFilledBox(),
                        (pose, fillBuffer) -> {
                            for (Entry e : entries) {
                                filledBox(pose.pose(), fillBuffer, e.box(), e.r(), e.g(), e.b(),
                                        e.a() * fillAlphaScale);
                            }
                        });
            }
            if (outline) {
                McRender.inCameraSpace(context,
                        throughWalls ? ThroughWalls.LINES : RenderTypes.LINES_TRANSLUCENT,
                        (pose, lineBuffer) -> {
                            for (Entry e : entries) {
                                lineBox(pose, lineBuffer, e.box(), e.r(), e.g(), e.b(), 1f, 2f);
                            }
                        });
            }
        } catch (Throwable t) {
            if (!renderFailureLogged) {
                renderFailureLogged = true;
                LOGGER.error("[Etherwarp] Box rendering failed - waypoints will not be drawn this frame", t);
            }
        }
    }

    private static boolean renderFailureLogged = false;

    private static final int[] EDGES = {
            0, 1, 1, 5, 5, 4, 4, 0,
            3, 2, 2, 6, 6, 7, 7, 3,
            0, 3, 1, 2, 5, 6, 4, 7
    };

    private static void lineBox(PoseStack.Pose pose, VertexConsumer buffer, AABB b, float r, float g, float bl,
                                float a, float width) {
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
