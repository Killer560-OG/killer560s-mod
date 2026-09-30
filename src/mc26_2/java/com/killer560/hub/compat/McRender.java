package com.killer560.hub.compat;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.world.phys.Vec3;

/**
 * World-space drawing, for Minecraft 26.2.
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
        return context.levelState().cameraRenderState.pos;
    }

    /**
     * Runs {@code geometry} with the pose translated into camera space and a consumer for {@code type}.
     *
     * <p>Every world-space draw in the mod had the same seven lines around it - null-check the buffer source,
     * push, translate by the negated camera, take the pose, get the buffer, draw, pop. They live here now, so
     * a version that changes how any of that works changes one place.
     */
    public static void inCameraSpace(LevelRenderContext context, RenderType type, Geometry geometry) {
        PoseStack poseStack = context.poseStack();
        Vec3 cam = cameraPos(context);
        poseStack.pushPose();
        poseStack.translate(-cam.x, -cam.y, -cam.z);
        // order(0) is the default ordering bucket. submitCustomGeometry captures the pose at submit time and
        // hands it back to the callback, so popping immediately afterwards is correct even though the frame
        // graph runs the callback later.
        context.submitNodeCollector().order(0).submitCustomGeometry(poseStack, type, geometry::draw);
        poseStack.popPose();
    }
    /**
     * Draws text in the world.
     *
     * <p>26.2 removes {@code Font.drawInBatch} entirely and replaces it with a submit. The argument order below
     * is NOT a guess: {@code submitText} takes four trailing ints where {@code drawInBatch} took three, and the
     * order was read off the components of {@code TextFeatureRenderer.Submit}, the record it builds -
     * {@code lightCoords, color, backgroundColor, outlineColor}. Getting that wrong would have compiled and
     * drawn text in the wrong colour.
     *
     * <p>{@code font} is unused here - 26.2 needs only the sequence - but stays in the signature so both
     * versions' copies match, which is the rule for this package.
     */
    public static void drawText(LevelRenderContext context, net.minecraft.client.gui.Font font,
                                net.minecraft.network.chat.Component text, float x, float y, int colour,
                                boolean shadow, PoseStack poseStack, net.minecraft.client.gui.Font.DisplayMode mode,
                                int background, int light) {
        drawText(context, font, text.getVisualOrderText(), x, y, colour, shadow, poseStack, mode, background,
                light);
    }

    /**
     * As {@link #drawText}, for a plain string.
     *
     * <p>26.2's submit path speaks only {@code FormattedCharSequence}, so the wrap happens here rather than at
     * eighteen call sites. {@code Style.EMPTY} matches what {@code Font.drawInBatch(String, ...)} did on
     * 26.1.2 - it applied no style of its own.
     */
    public static void drawText(LevelRenderContext context, net.minecraft.client.gui.Font font, String text,
                                float x, float y, int colour, boolean shadow, PoseStack poseStack,
                                net.minecraft.client.gui.Font.DisplayMode mode, int background, int light) {
        drawText(context, font, net.minecraft.util.FormattedCharSequence.forward(
                text, net.minecraft.network.chat.Style.EMPTY), x, y, colour, shadow, poseStack, mode,
                background, light);
    }

    /** As {@link #drawText}, for an already-ordered sequence - what 26.2 submits natively. */
    public static void drawText(LevelRenderContext context, net.minecraft.client.gui.Font font,
                                net.minecraft.util.FormattedCharSequence text, float x, float y, int colour,
                                boolean shadow, PoseStack poseStack,
                                net.minecraft.client.gui.Font.DisplayMode mode, int background, int light) {
        context.submitNodeCollector().order(0).submitText(poseStack, x, y, text, shadow, mode, light, colour,
                background, 0);
    }

}
