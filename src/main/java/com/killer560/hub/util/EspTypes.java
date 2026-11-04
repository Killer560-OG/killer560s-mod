package com.killer560.hub.util;

import com.killer560.hub.compat.McRender;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;

import java.util.Optional;

/**
 * The mod's world-space highlight render types, all of which WRITE depth (killer560, 2026-11-03: "make sure that blaze fix
 * applies to all things that could have a similar issue"; docs/LESSONS-GUI.md "A highlight box that writes no depth").
 * Water is drawn after the features pass on 26.3, so a box or line that left the depth buffer alone had water behind it
 * painted over it. {@link #FILLED} / {@link #LINES} are the depth-tested ones (vanilla's {@code debugFilledBox} and
 * {@code LINES_TRANSLUCENT} replaced), {@link #FILLED_THRU} / {@link #LINES_THRU} the through-walls ones (the depth test always
 * passes, depth is still written). Each has its Improved Transparency (OIT) pipelines, or 26.3 with that option on throws every frame.
 * Every feature that used its own no-depth copy now points at the THRU pair, so there is one construction to keep right.
 * <p>
 * {@link #init()} must run at client init, before the renderer precompiles its pipeline list (called from the client entrypoint).
 */
public final class EspTypes {

    private EspTypes() {
    }

    private static final Optional<DepthStencilState> WRITE_DEPTH_ALWAYS = Optional.of(new DepthStencilState(
            CompareOp.ALWAYS_PASS, true));

    /** Depth-tested (default test) and depth-writing translucent debug fill. */
    public static final RenderType FILLED = RenderType.create("killer560smod_esp_filled_depth",
            McRender.withVanillaOit(RenderSetup.builder(RenderPipelines.register(RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
                    .withLocation(Identifier.fromNamespaceAndPath("killer560smod", "pipeline/esp_filled_depth"))
                    .withCull(false)
                    .withDepthStencilState(DepthStencilState.DEFAULT)
                    .build())).sortOnUpload(), false).createRenderSetup());

    /** Depth-tested and depth-writing lines. */
    public static final RenderType LINES = RenderType.create("killer560smod_esp_lines_depth",
            McRender.withVanillaOit(McRender.sortLinesOnUpload(RenderSetup.builder(RenderPipelines.register(
                    McRender.linesColorTarget(RenderPipeline.builder(RenderPipelines.LINES_SNIPPET))
                            .withLocation(Identifier.fromNamespaceAndPath("killer560smod", "pipeline/esp_lines_depth"))
                            .withCull(false)
                            .withDepthStencilState(DepthStencilState.DEFAULT)
                            .build()))), true).createRenderSetup());

    /** Through walls (always passes) but writes depth. */
    public static final RenderType FILLED_THRU = RenderType.create("killer560smod_esp_filled_thru",
            McRender.withOitNoDepth(RenderSetup.builder(RenderPipelines.register(RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
                    .withLocation(Identifier.fromNamespaceAndPath("killer560smod", "pipeline/esp_filled_thru"))
                    .withCull(false)
                    .withDepthStencilState(WRITE_DEPTH_ALWAYS)
                    .build())).sortOnUpload(), "killer560smod_esp_filled_thru", false).createRenderSetup());

    /** Through-walls lines that write depth. */
    public static final RenderType LINES_THRU = RenderType.create("killer560smod_esp_lines_thru",
            McRender.withOitNoDepth(McRender.sortLinesOnUpload(RenderSetup.builder(RenderPipelines.register(
                    McRender.linesColorTarget(RenderPipeline.builder(RenderPipelines.LINES_SNIPPET))
                            .withLocation(Identifier.fromNamespaceAndPath("killer560smod", "pipeline/esp_lines_thru"))
                            .withCull(false)
                            .withDepthStencilState(WRITE_DEPTH_ALWAYS)
                            .build()))), "killer560smod_esp_lines_thru", true).createRenderSetup());

    /** Forces the class to load (registers the pipelines). Safe to call any number of times. */
    public static void init() {
    }

    public static RenderType fill(boolean throughWalls) { return throughWalls ? FILLED_THRU : FILLED; }

    public static RenderType lines(boolean throughWalls) { return throughWalls ? LINES_THRU : LINES; }
}
