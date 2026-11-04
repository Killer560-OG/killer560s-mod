package com.killer560.hub.p3nav;

import static com.killer560.hub.util.BoxVerts.*;
import com.killer560.hub.util.WorldRenderUtils;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;

import java.util.Optional;
import com.killer560.hub.compat.McRender;

/**
 * Box drawing for the P3 navigation aids. Depth-tested boxes go straight through this mod's existing
 * {@link WorldRenderUtils}; through-walls boxes (Terminal ESP, cheat build only) need a pipeline with no
 * depth test, built the same way {@code mobesp/EspRenderer} builds its two - copies of vanilla's LINES /
 * DEBUG_FILLED snippets with {@code withDepthStencilState(Optional.empty())}, which is how NoammAddons'
 * 26.1.2 {@code NoammRenderPipelines} does it. The pipeline ids are p3nav-specific so they can't collide
 * with Dungeon ESP's own registrations.
 * <p>
 * (The vertex layout below is the same Odin-derived one {@code WorldRenderUtils}/{@code EspRenderer} use.
 * If the main session would rather have one shared renderer, {@code EspRenderer.outline}/{@code filled}
 * are identical apart from the fixed 0.35 fill alpha - see the report.)
 */
final class P3NavRenderer {

    private P3NavRenderer() {
    }

    /** Called from {@link P3NavFeature#register()} so the pipelines exist before the renderer precompiles. */
    static void init() {
        if (com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            RenderType unused = com.killer560.hub.util.EspTypes.LINES_THRU;
        }
    }

    /** Fill is drawn at 35% of the color's own alpha so whatever is inside the box stays readable. */
    private static final float FILL_ALPHA_SCALE = 0.35f;

    static void draw(LevelRenderContext context, AABB box, int argb, P3NavConfig.Style style, float lineWidth,
                     boolean throughWalls) {
        boolean fill = style != P3NavConfig.Style.OUTLINE;
        boolean outline = style != P3NavConfig.Style.FILLED;
        if (fill) {
            float[] c = rgba(argb, FILL_ALPHA_SCALE);
            if (throughWalls) {
                filledThroughWalls(context, box, c);
            } else {
                WorldRenderUtils.renderFilledBox(context, box, c[0], c[1], c[2], c[3]);
            }
        }
        if (outline) {
            float[] c = rgba(argb, 1.0f);
            if (throughWalls) {
                outlineThroughWalls(context, box, c, lineWidth);
            } else {
                WorldRenderUtils.renderOutlineBox(context, box, c[0], c[1], c[2], c[3], lineWidth);
            }
        }
    }

    private static void outlineThroughWalls(LevelRenderContext context, AABB box, float[] c, float lineWidth) {
        McRender.inCameraSpace(context, com.killer560.hub.util.EspTypes.LINES_THRU, (pose, buffer) ->
                lineBox(pose, buffer, box, c[0], c[1], c[2], c[3], lineWidth));
    }

    private static void filledThroughWalls(LevelRenderContext context, AABB box, float[] c) {
        McRender.inCameraSpace(context, com.killer560.hub.util.EspTypes.FILLED_THRU, (pose, buffer) ->
                filledBox(pose.pose(), buffer, box, c[0], c[1], c[2], c[3]));
    }

}
