package com.killer560.hub.thorn;

import static com.killer560.hub.util.BoxVerts.*;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;

import java.util.Optional;
import com.killer560.hub.compat.McRender;

/**
 * Thorn ESP box drawing - a copy of {@code mobesp.EspRenderer} (package-private there, and that package belongs to
 * another feature) with its own pipeline ids. If EspRenderer is ever made public, delete this and call it instead.
 * <p>
 * Original doc: Dungeon ESP box drawing. Depth-tested boxes use vanilla's own {@code RenderTypes.LINES_TRANSLUCENT} /
 * {@code debugFilledBox()} (same as {@code WorldRenderUtils}); through-walls boxes use copies of those two pipelines
 * with the depth/stencil state removed - exactly how NoammAddons' 26.1.2 {@code NoammRenderPipelines}
 * ({@code lines_through_walls} / {@code filled_through_walls}) does it ({@code withDepthStencilState(Optional.empty())},
 * signature javap-verified on the 26.1.2 jar). Vertex layout is the same Odin-derived one {@code WorldRenderUtils} uses.
 */
final class ThornEspRenderer {

    private ThornEspRenderer() {
    }

    /** Called from client init (like Noamm registering its pipelines at init) so the two pipelines are in
     *  {@code RenderPipelines}' static list before the renderer precompiles it. */
    static void init() { RenderType unused = com.killer560.hub.util.EspTypes.LINES_THRU; }

    static void outline(LevelRenderContext context, AABB box, int argb, float lineWidth, boolean throughWalls) {
        float[] c = rgba(argb, 1.0f);
        McRender.inCameraSpace(context, com.killer560.hub.util.EspTypes.lines(throughWalls), (pose, buffer) -> {
            lineBox(pose, buffer, box, c[0], c[1], c[2], c[3], lineWidth);
        });
    }

    /** Fill is drawn at 35% of the color's own alpha so the mob stays visible inside it. */
    static void filled(LevelRenderContext context, AABB box, int argb, boolean throughWalls) {
        float[] c = rgba(argb, 0.35f);
        McRender.inCameraSpace(context, com.killer560.hub.util.EspTypes.fill(throughWalls), (pose, buffer) -> {
            filledBox(pose.pose(), buffer, box, c[0], c[1], c[2], c[3]);
        });
    }

}
