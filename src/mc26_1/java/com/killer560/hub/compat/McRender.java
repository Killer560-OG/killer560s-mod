package com.killer560.hub.compat;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.world.phys.Vec3;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;

/**
 * World-space drawing, for Minecraft 26.1.2.
 *
 * <p>This is the part of the 26.2 port that looked like a rewrite and turned out not to be.
 * {@code MultiBufferSource} does not exist in 26.2 at all - zero occurrences in the jar - and
 * {@code LevelRenderer.render} now works through a frame graph and render-state objects instead of handing
 * out vertex buffers. But {@code OrderedSubmitNodeCollector.submitCustomGeometry} takes a callback that
 * receives a {@code VertexConsumer}, so the mod's own vertex maths is untouched and only the way it gets hold
 * of a consumer differs. {@code RenderType} and {@code RenderTypes} sit at the same package in both versions,
 * so the signatures here are genuinely identical rather than merely similar.
 *
 * <p><b>The camera.</b> 26.2 makes {@code GameRenderer.mainCamera} private and Fabric does not widen it, so
 * it cannot be read the old way - checked with a compile probe, not assumed. What does work is
 * {@code levelState().cameraRenderState.pos}, which Fabric's transitive access wideners open. That is only
 * reachable from inside a render event, so {@link #cameraPos} takes the context; every one of the mod's 29
 * camera call sites is already inside one, which was verified before this signature was settled.
 */
public final class McRender {

    private McRender() {
    }

    /** What a caller draws once the pose is in camera space. */
    public interface Geometry {
        void draw(PoseStack.Pose pose, VertexConsumer buffer);
    }

    /** Where the camera is this frame. */
    public static Vec3 cameraPos(LevelRenderContext context) {
        return Minecraft.getInstance().gameRenderer.getMainCamera().position();
    }

    /**
     * Runs {@code geometry} with the pose translated into camera space and a consumer for {@code type}.
     *
     * <p>Every world-space draw in the mod had the same seven lines around it - null-check the buffer source,
     * push, translate by the negated camera, take the pose, get the buffer, draw, pop. They live here now, so
     * a version that changes how any of that works changes one place.
     */
    public static void inCameraSpace(LevelRenderContext context, RenderType type, Geometry geometry) {
        MultiBufferSource.BufferSource bufferSource = context.bufferSource();
        if (bufferSource == null) {
            return;
        }
        PoseStack poseStack = context.poseStack();
        Vec3 cam = cameraPos(context);
        poseStack.pushPose();
        poseStack.translate(-cam.x, -cam.y, -cam.z);
        geometry.draw(poseStack.last(), bufferSource.getBuffer(type));
        poseStack.popPose();
    }
    /**
     * Draws text in the world.
     *
     * <p>26.2 removes {@code Font.drawInBatch} entirely - text is prepared and submitted now - so the twenty
     * call sites in this mod go through here.
     */
    public static void drawText(LevelRenderContext context, net.minecraft.client.gui.Font font,
                                net.minecraft.network.chat.Component text, float x, float y, int colour,
                                boolean shadow, PoseStack poseStack, net.minecraft.client.gui.Font.DisplayMode mode,
                                int background, int light) {
        MultiBufferSource.BufferSource bufferSource = context.bufferSource();
        if (bufferSource == null) {
            return;
        }
        font.drawInBatch(text, x, y, colour, shadow, poseStack.last().pose(), bufferSource, mode, background,
                light);
    }

    /** As {@link #drawText}, for a plain string. */
    public static void drawText(LevelRenderContext context, net.minecraft.client.gui.Font font, String text,
                                float x, float y, int colour, boolean shadow, PoseStack poseStack,
                                net.minecraft.client.gui.Font.DisplayMode mode, int background, int light) {
        MultiBufferSource.BufferSource bufferSource = context.bufferSource();
        if (bufferSource == null) {
            return;
        }
        font.drawInBatch(text, x, y, colour, shadow, poseStack.last().pose(), bufferSource, mode, background,
                light);
    }

    /** As {@link #drawText}, for an already-ordered sequence. */
    public static void drawText(LevelRenderContext context, net.minecraft.client.gui.Font font,
                                net.minecraft.util.FormattedCharSequence text, float x, float y, int colour,
                                boolean shadow, PoseStack poseStack,
                                net.minecraft.client.gui.Font.DisplayMode mode, int background, int light) {
        MultiBufferSource.BufferSource bufferSource = context.bufferSource();
        if (bufferSource == null) {
            return;
        }
        font.drawInBatch(text, x, y, colour, shadow, poseStack.last().pose(), bufferSource, mode, background,
                light);
    }

}
