package com.killer560.hub.mobesp;

import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;
import com.killer560.hub.compat.McRender;

/**
 * Dungeon ESP box drawing. Depth-tested boxes use vanilla's own {@code RenderTypes.LINES_TRANSLUCENT} /
 * {@code debugFilledBox()} (same as {@code WorldRenderUtils}); through-walls boxes use copies of those two pipelines
 * with the depth/stencil state removed - exactly how NoammAddons' 26.1.2 {@code NoammRenderPipelines}
 * ({@code lines_through_walls} / {@code filled_through_walls}) does it ({@code withDepthStencilState(Optional.empty())},
 * signature javap-verified on the 26.1.2 jar). Vertex layout is the same Odin-derived one {@code WorldRenderUtils} uses.
 */
final class EspRenderer {

    private EspRenderer() {
    }

    /** Called from client init (like Noamm registering its pipelines at init) so the two pipelines are in
     *  {@code RenderPipelines}' static list before the renderer precompiles it. */
    static void init() {
        RenderType unused = ThroughWalls.LINES;
    }

    /** Holder idiom - built once, on {@link #init()}. */
    private static final class ThroughWalls {
        static final RenderType LINES = RenderType.create("killer560smod_esp_lines_through_walls",
                RenderSetup.builder(RenderPipelines.register(RenderPipeline.builder(RenderPipelines.LINES_SNIPPET)
                        .withLocation(Identifier.fromNamespaceAndPath("killer560smod", "pipeline/esp_lines_through_walls"))
                        .withCull(false)
                        .withDepthStencilState(Optional.<DepthStencilState>empty())
                        .build())).createRenderSetup());

        static final RenderType FILLED = RenderType.create("killer560smod_esp_filled_through_walls",
                RenderSetup.builder(RenderPipelines.register(RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
                        .withLocation(Identifier.fromNamespaceAndPath("killer560smod", "pipeline/esp_filled_through_walls"))
                        .withCull(false)
                        .withDepthStencilState(Optional.<DepthStencilState>empty())
                        .build())).sortOnUpload().createRenderSetup());
    }

    /** The line type an outline uses: the no-depth copy through walls, vanilla's depth-tested one otherwise. */
    static RenderType lineType(boolean throughWalls) {
        return throughWalls ? ThroughWalls.LINES : RenderTypes.LINES_TRANSLUCENT;
    }

    /** The fill type {@link #filled} uses. */
    static RenderType fillType(boolean throughWalls) {
        return throughWalls ? ThroughWalls.FILLED : RenderTypes.debugFilledBox();
    }

    /** The color's alpha as {@link #rgba} reads it (0 means opaque). */
    static float alpha(int argb) {
        float a = ((argb >>> 24) & 0xFF) / 255f;
        return a <= 0f ? 1f : a;
    }

    /** A single line from {@code from} to {@code to} - the Wither ESP tracer, drawn in the ESP's own color. */
    static void tracer(LevelRenderContext context, Vec3 from, Vec3 to, int argb, float width, boolean throughWalls) {
        float[] c = rgba(argb, 1.0f);
        McRender.inCameraSpace(context, throughWalls ? ThroughWalls.LINES : RenderTypes.LINES_TRANSLUCENT, (pose, buffer) -> {
            float sx = (float) from.x, sy = (float) from.y, sz = (float) from.z;
            float ex = (float) to.x, ey = (float) to.y, ez = (float) to.z;
            float dx = ex - sx, dy = ey - sy, dz = ez - sz;
            buffer.addVertex(pose, sx, sy, sz).setColor(c[0], c[1], c[2], c[3]).setNormal(pose, dx, dy, dz).setLineWidth(width);
            buffer.addVertex(pose, ex, ey, ez).setColor(c[0], c[1], c[2], c[3]).setNormal(pose, dx, dy, dz).setLineWidth(width);
        });
    }

    private static float[] rgba(int argb, float alphaScale) {
        float a = ((argb >>> 24) & 0xFF) / 255f;
        if (a <= 0f) {
            a = 1f;
        }
        return new float[]{((argb >> 16) & 0xFF) / 255f, ((argb >> 8) & 0xFF) / 255f, (argb & 0xFF) / 255f, a * alphaScale};
    }
}
