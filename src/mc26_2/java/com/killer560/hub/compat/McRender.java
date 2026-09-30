package com.killer560.hub.compat;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BindGroupLayouts;
import org.joml.Vector4f;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector3fc;

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
     * How the camera is turned this frame - {@code Camera.rotation()}'s quaternion, for billboarding.
     *
     * <p>{@code CameraRenderState.orientation} is not merely similar to it: 26.2's own
     * {@code Camera.extractRenderState} fills that field with {@code orientation.set(rotation())}, read off the
     * bytecode. The instance is the render state's own and vanilla reuses it between frames, so a caller must
     * not keep it - every caller in this mod feeds it straight to {@code PoseStack.mulPose}.
     */
    public static Quaternionf cameraRotation(LevelRenderContext context) {
        return context.levelState().cameraRenderState.orientation;
    }

    /** The camera's pitch, in degrees - {@code Camera.xRot()}. */
    public static float cameraXRot(LevelRenderContext context) {
        return context.levelState().cameraRenderState.xRot;
    }

    /** The camera's yaw, in degrees - {@code Camera.yRot()}. */
    public static float cameraYRot(LevelRenderContext context) {
        return context.levelState().cameraRenderState.yRot;
    }

    /**
     * Unit vector the camera is looking along - {@code Camera.forwardVector()}.
     *
     * <p>The render state does NOT carry this one, so it is rebuilt the way {@code Camera} builds it:
     * {@code Camera.setRotation} does {@code FORWARDS.rotate(rotation, forwards)} with the class's private
     * {@code FORWARDS} constant, which the static initialiser sets to {@code (0, 0, -1)} - both facts read off
     * {@code javap -c} on 26.2's own {@code Camera}, not assumed from the 26.1.2 source. Minus Z, not plus:
     * getting that sign wrong would have compiled and pointed every tracer backwards.
     *
     * <p>A fresh vector each call, because {@code rotate} writes into its destination and the camera's own
     * {@code forwards} field is not reachable here.
     */
    public static Vector3fc cameraForward(LevelRenderContext context) {
        return new Vector3f(0.0F, 0.0F, -1.0F).rotate(cameraRotation(context));
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


    // --------------------------------------------------------------------------- render targets and GPU buffers
    //
    // The four differences the two cosmetic post-process features (Custom Scoreboard's background blur and
    // Motion Blur) ran into. All four are the same API doing the same thing under a different name, verified
    // with javap against both jars - none of them needed the features stubbing out, and neither feature's GLSL
    // changes at all: 26.2 still wants "#version 330", still ships
    // assets/minecraft/shaders/include/dynamictransforms.glsl and projection.glsl for #moj_import, and still
    // declares "uniform sampler2D Sampler0" (see core/text.fsh).

    /**
     * The framebuffer the world was drawn into this frame.
     *
     * <p>26.2 removes {@code Minecraft.getMainRenderTarget()}. It is NOT replaced by a GpuSurface model, as the
     * port notes claimed: {@code GameRenderer} keeps a private {@code mainRenderTarget} field and exposes it as
     * the public method {@code mainRenderTarget()} - the old name minus the {@code get}. Found by listing
     * {@code GameRenderer}, and it is still a real {@code MainTarget}, so everything the two blur features do
     * with it (width, height, {@code getColorTexture}, {@code getColorTextureView}) is unchanged.
     * {@code Minecraft.windowSurface()} is a different thing - the swapchain the finished frame is presented to -
     * and is NOT what either feature wants to sample.
     */
    public static RenderTarget mainRenderTarget(Minecraft client) {
        return client.gameRenderer.mainRenderTarget();
    }

    /**
     * Declares that a pipeline samples texture unit 0.
     *
     * <p>26.2 drops {@code RenderPipeline.Builder.withSampler(String)} for the bind-group model, where the same
     * declaration is {@code withBindGroupLayout(BindGroupLayouts.SAMPLER0)} - and {@code BindGroupLayouts.SAMPLER0}
     * is, in its own bytecode, exactly {@code BindGroupLayout.builder().withSampler("Sampler0").build()}, which is
     * why this is a rename and not a shader change.
     */
    public static RenderPipeline.Builder withSampler0(RenderPipeline.Builder builder) {
        return builder.withBindGroupLayout(BindGroupLayouts.SAMPLER0);
    }

    /**
     * An off-screen colour target the size of the window, in the same format as the main one.
     *
     * <p>26.2's {@code TextureTarget} constructor gained a {@code GpuFormat}. {@code RGBA8_UNORM} is not a guess:
     * it is what {@code MainTarget}'s own constructor passes up to {@code RenderTarget}, read off its bytecode -
     * and it has to match, because the whole point of this target is to be a {@code copyTextureToTexture}
     * destination for the main one.
     */
    public static TextureTarget newTextureTarget(String label, int width, int height, boolean useDepth) {
        return new TextureTarget(label, width, height, useDepth, GpuFormat.RGBA8_UNORM);
    }

    /**
     * Clears a colour texture to transparent black - the {@code 0} that 26.1.2's {@code clearColorTexture} took
     * as a packed ARGB int, which 26.2 takes as a {@code Vector4fc}.
     */
    public static void clearToZero(GpuTexture texture) {
        RenderSystem.getDevice().createCommandEncoder()
                .clearColorTexture(texture, new Vector4f(0.0F, 0.0F, 0.0F, 0.0F));
    }

    /**
     * Writes one std140 vec4 into a mapped uniform buffer.
     *
     * <p>26.2 moves mapping off the command encoder and onto the buffer: {@code CommandEncoder.mapBuffer(buffer,
     * read, write)} returning a {@code GpuBuffer.MappedView} became {@code GpuBuffer.map(read, write)} returning
     * a {@code GpuBufferSlice.MappedView}. Both are {@code AutoCloseable} with a {@code data()} of
     * {@code ByteBuffer} and {@code Std140Builder} is unchanged, but the view TYPE differs, so the
     * try-with-resources itself has to live on this side of the facade rather than at the call site.
     */
    public static void writeUniformVec4(GpuBuffer buffer, float x, float y, float z, float w) {
        try (GpuBufferSlice.MappedView view = buffer.map(false, true)) {
            Std140Builder.intoBuffer(view.data()).putVec4(x, y, z, w);
        }
    }

}
