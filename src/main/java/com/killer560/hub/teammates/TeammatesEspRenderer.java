package com.killer560.hub.teammates;

import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;

import java.util.Optional;

/**
 * Teammate Highlight box drawing - a copy of {@code thorn.ThornEspRenderer}, which is itself a copy of
 * {@code mobesp.EspRenderer} (both package-private, in packages that belong to other features), with its own
 * pipeline ids. If EspRenderer is ever made public, delete this and call it instead.
 * <p>
 * Original doc: Dungeon ESP box drawing. Depth-tested boxes use vanilla's own {@code RenderTypes.LINES_TRANSLUCENT} /
 * {@code debugFilledBox()} (same as {@code WorldRenderUtils}); through-walls boxes use copies of those two pipelines
 * with the depth/stencil state removed - exactly how NoammAddons' 26.1.2 {@code NoammRenderPipelines}
 * ({@code lines_through_walls} / {@code filled_through_walls}) does it ({@code withDepthStencilState(Optional.empty())},
 * signature javap-verified on the 26.1.2 jar). Vertex layout is the same Odin-derived one {@code WorldRenderUtils} uses.
 */
final class TeammatesEspRenderer {

    private TeammatesEspRenderer() {
    }

    /** Called from client init (like Noamm registering its pipelines at init) so the two pipelines are in
     *  {@code RenderPipelines}' static list before the renderer precompiles it. */
    static void init() {
        RenderType unused = ThroughWalls.LINES;
    }

    /** Holder idiom - built once, on {@link #init()}. */
    private static final class ThroughWalls {
        static final RenderType LINES = RenderType.create("killer560smod_teammates_esp_lines_through_walls",
                RenderSetup.builder(RenderPipelines.register(RenderPipeline.builder(RenderPipelines.LINES_SNIPPET)
                        .withLocation(Identifier.fromNamespaceAndPath("killer560smod", "pipeline/teammates_esp_lines_through_walls"))
                        .withCull(false)
                        .withDepthStencilState(Optional.<DepthStencilState>empty())
                        .build())).createRenderSetup());

        static final RenderType FILLED = RenderType.create("killer560smod_teammates_esp_filled_through_walls",
                RenderSetup.builder(RenderPipelines.register(RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
                        .withLocation(Identifier.fromNamespaceAndPath("killer560smod", "pipeline/teammates_esp_filled_through_walls"))
                        .withCull(false)
                        .withDepthStencilState(Optional.<DepthStencilState>empty())
                        .build())).sortOnUpload().createRenderSetup());
    }

    /** The line type an outline uses: the no-depth copy through walls, vanilla's depth-tested one otherwise. */
    static RenderType lineType(boolean throughWalls) {
        return throughWalls ? ThroughWalls.LINES : RenderTypes.LINES_TRANSLUCENT;
    }

    /** The fill type for the batched fill call. */
    static RenderType fillType(boolean throughWalls) {
        return throughWalls ? ThroughWalls.FILLED : RenderTypes.debugFilledBox();
    }

    /** The color's alpha as the box batch reads it (0 means opaque). */
    static float alpha(int argb) {
        float a = ((argb >>> 24) & 0xFF) / 255f;
        return a <= 0f ? 1f : a;
    }
}
